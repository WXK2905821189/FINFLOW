package com.finance.system.bankdata.adapter.cmb;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.bankdata.BankDataQueryService;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.BankDataBalance;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.entity.SysUserRole;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.BankDataBalanceMapper;
import com.finance.system.domain.mapper.BankDataStatementMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.domain.mapper.SysUserMapper;
import com.finance.system.domain.mapper.SysUserRoleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant isolation over the REAL bank direct-link path (architecture review 2026-09-03 P0
 * follow-up). The V02 isolation assertions only cover the NOT_CONFIGURED gate; this context
 * boots the genuine {@link RealCmbBankDataAdapter} against the in-process {@link FakeCmbServer}
 * (full SM2/SM4 signing, encryption and bank-side signature verification) and proves that
 * statements/balances collected through the real wire protocol stay company-scoped end to end:
 *
 * <ul>
 *   <li>each tenant syncs its own CMB account through the fake gateway (HTTP + crypto real);</li>
 *   <li>normalized rows land with the correct companyId in bank_data_statement/bank_data_balance;</li>
 *   <li>projections (/api/bank-data/balances|statements) only ever serve the caller's company;</li>
 *   <li>a foreign requestId cannot leak rows — the projection falls back to the caller's own
 *       REAL tasks, never the other company's;</li>
 *   <li>cross-company task detail reads stay a plain 404 at the service boundary.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CmbRealPathTenantIsolationIntegrationTest {

    private static final String PASSWORD = "Test@12345";
    /** Canned rows inside the fake gateway carry this account number. */
    private static final String COMPANY_B_ACCOUNT_NO = "769900000010370";
    private static final String COMPANY_A_ACCOUNT_NO = "769900000010371";

    private static FakeCmbServer bank;

    @DynamicPropertySource
    static void realCmbGateway(DynamicPropertyRegistry registry) {
        try {
            bank = new FakeCmbServer();
        } catch (IOException e) {
            throw new IllegalStateException("fake CMB gateway failed to start", e);
        }
        CmbAdapterProperties client = bank.clientProperties();
        registry.add("bankdata.adapter.cmb.real-enabled", () -> "true");
        registry.add("bankdata.adapter.call.real-adapters-enabled", () -> "true");
        registry.add("bankdata.adapter.cmb.url", client::getUrl);
        registry.add("bankdata.adapter.cmb.uid", client::getUid);
        registry.add("bankdata.adapter.cmb.private-key", client::getPrivateKey);
        registry.add("bankdata.adapter.cmb.public-key", client::getPublicKey);
        registry.add("bankdata.adapter.cmb.sym-key", client::getSymKey);
        registry.add("bankdata.adapter.cmb.connect-timeout-ms", () -> "5000");
        registry.add("bankdata.adapter.cmb.read-timeout-ms", () -> "5000");
    }

    @AfterAll
    static void stopFakeGateway() {
        if (bank != null) {
            bank.close();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CompanyMapper companyMapper;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private SysUserRoleMapper userRoleMapper;
    @Autowired
    private BankAccountMapper bankAccountMapper;
    @Autowired
    private BankDataSyncTaskMapper syncTaskMapper;
    @Autowired
    private BankDataStatementMapper statementMapper;
    @Autowired
    private BankDataBalanceMapper balanceMapper;
    @Autowired
    private BankDataQueryService bankDataQueryService;

    @Test
    void realPathBankDataStaysIsolatedPerCompany() throws Exception {
        // Tenant B: fresh company, bankdata-enabled user, CMB account aligned with the canned rows.
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String uniqueSuffix = suffix.substring(0, 6);
        Company companyB = new Company();
        companyB.setCode("QA_CMB_" + suffix);
        companyB.setName("QA CMB company " + suffix);
        companyB.setStatus("ACTIVE");
        companyMapper.insert(companyB);

        SysUser userB = new SysUser();
        userB.setCompanyId(companyB.getId());
        userB.setUsername("qa_cmb_" + suffix);
        userB.setEmail("qa_cmb_" + suffix + "@finflow.test");
        userB.setPasswordHash(passwordEncoder.encode(PASSWORD));
        userB.setStatus("ACTIVE");
        userMapper.insert(userB);
        // Role 2 = baseline tenant user; role 3 = bankdata operator (same pair as V02).
        userRoleMapper.insert(new SysUserRole(userB.getId(), 2L));
        userRoleMapper.insert(new SysUserRole(userB.getId(), 3L));

        BankAccount accountB = insertCmbAccount(companyB.getId(), COMPANY_B_ACCOUNT_NO);
        // Company A (admin's tenant): its own CMB account, different account number.
        BankAccount accountA = insertCmbAccount(1L, COMPANY_A_ACCOUNT_NO);

        String adminToken = login("admin", "Admin@123");
        String companyBToken = login(userB.getUsername(), PASSWORD);

        bank.respondBalance(balanceResponse(COMPANY_A_ACCOUNT_NO, "12345.67"));
        bank.respondStatement(statementResponse("T000000000000" + uniqueSuffix + "A"));
        String requestIdA = "QA-CMB-A-" + uniqueSuffix;
        mockMvc.perform(post("/api/bank-sync-jobs")
                        .header("Authorization", bearer(adminToken))
                        .header("X-Request-Id", requestIdA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"STATEMENT_PULL\",\"bankAccountId\":" + accountA.getId()
                                + ",\"adapterCode\":\"CMB\",\"windowStart\":\"2026-09-01T00:00:00\","
                                + "\"windowEnd\":\"2026-09-02T00:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        bank.respondBalance(balanceResponse(COMPANY_B_ACCOUNT_NO, "88888.88"));
        bank.respondStatement(statementResponse("T000000000000" + uniqueSuffix + "B"));
        String requestIdB = "QA-CMB-B-" + uniqueSuffix;
        mockMvc.perform(post("/api/bank-sync-jobs")
                        .header("Authorization", bearer(companyBToken))
                        .header("X-Request-Id", requestIdB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"STATEMENT_PULL\",\"bankAccountId\":" + accountB.getId()
                                + ",\"adapterCode\":\"CMB\",\"windowStart\":\"2026-09-01T00:00:00\","
                                + "\"windowEnd\":\"2026-09-02T00:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        // Wire evidence: both syncs spoke the real CMB protocol through the fake gateway.
        assertTrue(bank.requests().size() >= 4, "balance + statement round trips per tenant sync");
        assertTrue(bank.requests().stream().allMatch(request -> request.signatureValid),
                "every collected request must carry a valid SM2 signature");

        BankDataSyncTask taskA = syncTaskMapper.selectOne(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getRequestId, requestIdA));
        BankDataSyncTask taskB = syncTaskMapper.selectOne(new LambdaQueryWrapper<BankDataSyncTask>()
                .eq(BankDataSyncTask::getRequestId, requestIdB));
        assertEquals("CMB", taskA.getAdapterCode());
        assertEquals("CMB", taskB.getAdapterCode());
        assertEquals(1L, taskA.getCompanyId());
        assertEquals(companyB.getId(), taskB.getCompanyId());

        assertEquals(1, statementMapper.selectCount(new LambdaQueryWrapper<BankDataStatement>()
                .eq(BankDataStatement::getCompanyId, 1L)
                .eq(BankDataStatement::getTaskId, taskA.getId())));
        assertEquals(1, balanceMapper.selectCount(new LambdaQueryWrapper<BankDataBalance>()
                .eq(BankDataBalance::getCompanyId, 1L)
                .eq(BankDataBalance::getTaskId, taskA.getId())));
        assertEquals(0, statementMapper.selectCount(new LambdaQueryWrapper<BankDataStatement>()
                .eq(BankDataStatement::getCompanyId, 1L)
                .eq(BankDataStatement::getTaskId, taskB.getId())));
        assertEquals(0, balanceMapper.selectCount(new LambdaQueryWrapper<BankDataBalance>()
                .eq(BankDataBalance::getCompanyId, 1L)
                .eq(BankDataBalance::getTaskId, taskB.getId())));

        // Landing zone: normalized rows must carry the owning company id, never the other tenant's.
        assertEquals(1, statementMapper.selectCount(new LambdaQueryWrapper<BankDataStatement>()
                .eq(BankDataStatement::getCompanyId, companyB.getId())
                .eq(BankDataStatement::getTaskId, taskB.getId())));
        assertEquals(0, statementMapper.selectCount(new LambdaQueryWrapper<BankDataStatement>()
                .eq(BankDataStatement::getCompanyId, companyB.getId())
                .eq(BankDataStatement::getTaskId, taskA.getId())));
        assertEquals(1, balanceMapper.selectCount(new LambdaQueryWrapper<BankDataBalance>()
                .eq(BankDataBalance::getCompanyId, companyB.getId())
                .eq(BankDataBalance::getTaskId, taskB.getId())));
        assertEquals(0, balanceMapper.selectCount(new LambdaQueryWrapper<BankDataBalance>()
                .eq(BankDataBalance::getCompanyId, companyB.getId())
                .eq(BankDataBalance::getTaskId, taskA.getId())));

        // Projection over the real path: REAL + enabled for the owning tenant, own rows only.
        // Lineage (mock-clean 2026-09-04): every row carries the bank request number and the
        // producing task's status — no simulated flag exists anymore.
        for (String resource : List.of("balances", "statements")) {
            mockMvc.perform(get("/api/bank-data/" + resource).param("requestId", requestIdB)
                            .header("Authorization", bearer(companyBToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("REAL"))
                    .andExpect(jsonPath("$.data.enabled").value(true))
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.records[0].bankRequestNo").isNotEmpty())
                    .andExpect(jsonPath("$.data.records[0].taskStatus").isNotEmpty());
        }

        // A foreign requestId must never leak the other tenant's rows: the projection falls
        // back to the caller's own REAL tasks, so each side still sees exactly its own row.
        for (String resource : List.of("balances", "statements")) {
            mockMvc.perform(get("/api/bank-data/" + resource).param("requestId", requestIdA)
                            .header("Authorization", bearer(companyBToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.status").value("REAL"));
            mockMvc.perform(get("/api/bank-data/" + resource).param("requestId", requestIdB)
                            .header("Authorization", bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.status").value("REAL"));
        }

        // Service boundary: cross-company task detail stays a plain 404.
        BusinessException leakB = assertThrows(BusinessException.class,
                () -> bankDataQueryService.getTaskDetail(userB.getId(), taskA.getId()));
        assertEquals(404, leakB.getCode());
        BusinessException leakA = assertThrows(BusinessException.class,
                () -> bankDataQueryService.getTaskDetail(1L, taskB.getId()));
        assertEquals(404, leakA.getCode());
    }

    private BankAccount insertCmbAccount(Long companyId, String accountNumber) {
        BankAccount account = new BankAccount();
        account.setCompanyId(companyId);
        account.setBankCode("CMB");
        account.setAccountName("QA CMB account " + companyId);
        account.setAccountNumber(accountNumber);
        account.setCurrency("CNY");
        account.setAvailableBalance(new BigDecimal("0.00"));
        account.setStatus("ACTIVE");
        bankAccountMapper.insert(account);
        return account;
    }

    /** Canned balance row; the adapter drops rows whose accnbr does not match the queried account. */
    private String balanceResponse(String accountNo, String avlblv) {
        return "{\"response\":{\"head\":{\"funcode\":\"NTQADINF\",\"resultcode\":\"SUC0000\","
                + "\"resultmsg\":\"\"},\"body\":{\"ntqadinfz\":[{\"accnbr\":\"" + accountNo + "\","
                + "\"accnam\":\"QA live account\",\"avlblv\":\"" + avlblv + "\",\"onlblv\":\"0.00\","
                + "\"hldblv\":\"0.00\",\"stscod\":\"A\",\"errcod\":\"SUC0000\",\"errtxt\":\"\"}]}}}";
    }

    /** One canned statement page: one credit row dated 20260901, continuation stops (ctnFlag=N). */
    private String statementResponse(String statementNo) {
        String z2 = "[{\"transDate\":\"20260901\",\"transTime\":\"101530\","
                + "\"transSequenceIdn\":\"" + statementNo + "\",\"loanCode\":\"C\","
                + "\"transAmount\":\"100.50\",\"currencyNbr\":\"10\",\"ctpAcctNbr\":\"957151020441242810\","
                + "\"ctpAcctName\":\"对手方公司\",\"businessText\":\"网银业务摘要\","
                + "\"remarkTextClt\":\" \",\"acctOnlineBal\":\"12446.17\"}]";
        return "{\"response\":{\"head\":{\"funcode\":\"trsQryByBreakPoint\",\"resultcode\":\"SUC0000\","
                + "\"resultmsg\":\"\"},\"body\":{"
                + "\"TRANSQUERYBYBREAKPOINT_Z1\":[{\"ctnFlag\":\"N\",\"queryAcctNbr\":\"\","
                + "\"debitNums\":\"0\",\"debitAmount\":\"0\",\"creditNums\":\"1\","
                + "\"creditAmount\":\"100.50\"}],"
                + "\"TRANSQUERYBYBREAKPOINT_Z2\":" + z2 + "}}}";
    }

    private String login(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
