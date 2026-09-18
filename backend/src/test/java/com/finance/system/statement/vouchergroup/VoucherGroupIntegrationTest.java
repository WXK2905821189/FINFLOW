package com.finance.system.statement.vouchergroup;

import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.Company;
import com.finance.system.domain.entity.StatementImportBatch;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.entity.SysUserRole;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.CompanyMapper;
import com.finance.system.domain.mapper.StatementImportBatchMapper;
import com.finance.system.domain.mapper.StatementRecordMapper;
import com.finance.system.domain.mapper.SysUserMapper;
import com.finance.system.domain.mapper.SysUserRoleMapper;
import com.finance.system.common.api.PageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 凭证中心（V34 ⑦）端到端 + 银行账户新增兜底（V34 ⑧）：
 *  - 账户新增只传户名+账号+银行 → 服务端兜底 CNY/0.00/ACTIVE；
 *  - voucher-groups 状态桶同时覆盖两条推送链路的口径（PUSHED/GL_PUSHED、FAILED/GL_FAILED）；
 *  - 待推送桶 = APPROVED 且未推送；关键词命中流水号/摘要；
 *  - W3（2026-09-18）可见域：cross-company 用户（admin/voucher:push 持有者）跨公司可见且可复核，
 *    无 cross-company 权限的用户仍只看本公司（Service 层直调验证隔离）。
 *
 * <p>全部数据走 API/独立 Mapper 插入（唯一后缀），不依赖 V1 seed 行（共享 H2 被多测试类改写）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class VoucherGroupIntegrationTest {

    private static final String UNIQUE_SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BankAccountMapper bankAccountMapper;
    @Autowired
    private StatementRecordMapper statementRecordMapper;
    @Autowired
    private StatementImportBatchMapper batchMapper;
    @Autowired
    private com.finance.system.statement.vouchergroup.VoucherGroupService voucherGroupService;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private SysUserRoleMapper userRoleMapper;
    @Autowired
    private CompanyMapper companyMapper;

    @Test
    void accountCreateFallsBackDefaultsWhenOnlyRequiredFieldsSent() throws Exception {
        String token = login();
        // V34 ⑧：币种/余额/状态全部省略 —— 服务端兜底（CNY / 0.00 / ACTIVE）。
        MvcResult result = mockMvc.perform(post("/api/bank-accounts")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\"CITIC\",\"accountName\":\"兜底测试账户-" + UNIQUE_SUFFIX + "\","
                                + "\"accountNumber\":\"6222" + UNIQUE_SUFFIX + "01\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode account = objectMapper.readTree(result.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("data");
        assertEquals("CNY", account.get("currency").asText(), "币种缺省兜底 CNY");
        assertEquals(0, new BigDecimal("0.00").compareTo(new BigDecimal(account.get("availableBalance").asText())),
                "初始余额缺省兜底 0.00");
        assertEquals("ACTIVE", account.get("status").asText(), "状态缺省兜底 ACTIVE");
    }

    @Test
    void voucherGroupBucketsCoverBothPushPipelines() throws Exception {
        String token = login();
        long accountId = createAccount(token, "凭证中心测试账户-" + UNIQUE_SUFFIX);

        String keyword = "KVGKW" + UNIQUE_SUFFIX;
        StatementRecord pending = insertStatement(accountId, "PENDING", null, null, null, "待复核草稿 " + keyword);
        StatementRecord toPush = insertStatement(accountId, "APPROVED", null, null, null, "待推送 " + keyword);
        StatementRecord glPushed = insertStatement(accountId, "APPROVED", "GL_PUSHED", "GL-" + UNIQUE_SUFFIX, null, "已推送GL链路");
        StatementRecord legacyPushed = insertStatement(accountId, "APPROVED", "PUSHED", "REC-" + UNIQUE_SUFFIX, null, "已推送出纳单链路");
        StatementRecord glFailed = insertStatement(accountId, "APPROVED", "GL_FAILED", null, "推送失败说明 " + keyword, null);

        // 他公司行：自造真实公司（不依赖 V1 seed——共享 H2 里 seed 行已被多测试类改写）。
        // W3 修复后 cross-company 用户（admin）必须可见——与制证口径对称
        // （此前恒等过滤本公司，导致他司草稿「制证成功、凭证中心看不到」）。
        Long otherCompanyId = insertCompany("KVGOTH").getId();
        insertStatementForCompany(otherCompanyId, accountId, "APPROVED", "PUSHED", "OTHER-" + UNIQUE_SUFFIX, null, "他公司行");

        // 1. ALL 桶：全部可见行（5 行本公司 + 他公司行），cross-company 用户跨公司可见
        JsonNode all = listGroups(token, "ALL", null);
        assertTrue(countBy(all, pending.getStatementNo()) == 1, "待复核行在 ALL 桶可见");
        assertTrue(countBy(all, toPush.getStatementNo()) == 1, "待推送行在 ALL 桶可见");
        assertTrue(countBy(all, glPushed.getStatementNo()) == 1);
        assertTrue(countBy(all, legacyPushed.getStatementNo()) == 1);
        assertTrue(countBy(all, glFailed.getStatementNo()) == 1);
        // W3 断言（2026-09-18 修正）：OTHER 行的 statementNo 是 fixture 生成的 KVG- 前缀，
        // "OTHER-"+UNIQUE_SUFFIX 是其 voucherNo——按 voucherNo 断言（原断言拿 voucherNo 值
        // 匹配 statementNo 字段，隔离版下为永真式，放开跨公司后才暴露）。
        // 跨公司可见性用唯一关键词精确命中：共享 H2 全公司行数可能超过一页（20 条），ALL 桶全量翻页不可靠。
        JsonNode otherHit = listGroups(token, "ALL", "OTHER-" + UNIQUE_SUFFIX);
        long otherRows = 0;
        for (JsonNode row : otherHit.get("records")) {
            if (("OTHER-" + UNIQUE_SUFFIX).equals(row.get("voucherNo").asText())) {
                otherRows++;
            }
        }
        assertEquals(1, otherRows, "W3 修复：cross-company 用户可见他公司凭证行");
        assertTrue(countBy(all, "not-exist-" + UNIQUE_SUFFIX) == 0);

        // 2. DRAFT 桶（reviewStatus=PENDING）
        JsonNode draft = listGroups(token, "DRAFT", null);
        assertEquals(1, countBy(draft, pending.getStatementNo()), "待复核桶只含 PENDING 草稿");
        assertEquals(0, countBy(draft, toPush.getStatementNo()));

        // 3. PENDING 桶（APPROVED 未推送）
        JsonNode pendingBucket = listGroups(token, "PENDING", null);
        assertEquals(1, countBy(pendingBucket, toPush.getStatementNo()), "待推送桶 = APPROVED 未推送");
        assertEquals(0, countBy(pendingBucket, glPushed.getStatementNo()));
        assertEquals(0, countBy(pendingBucket, pending.getStatementNo()));

        // 4. PUSHED 桶覆盖两条链路口径
        JsonNode pushed = listGroups(token, "PUSHED", null);
        assertEquals(1, countBy(pushed, glPushed.getStatementNo()), "GL_PUSHED 归入已推送桶");
        assertEquals(1, countBy(pushed, legacyPushed.getStatementNo()), "出纳单 PUSHED 归入已推送桶");
        assertEquals(0, countBy(pushed, glFailed.getStatementNo()));

        // 5. FAILED 桶
        JsonNode failed = listGroups(token, "FAILED", null);
        assertEquals(1, countBy(failed, glFailed.getStatementNo()));
        assertEquals(0, countBy(failed, glPushed.getStatementNo()));

        // 6. 关键词命中摘要
        JsonNode keywordHit = listGroups(token, "ALL", keyword);
        assertEquals(1, countBy(keywordHit, pending.getStatementNo()), "关键词命中摘要");
        assertEquals(0, countBy(keywordHit, glPushed.getStatementNo()), "未含关键词的行被过滤");

        // 7. 行内投影字段：公司名/账户脱敏/组内笔数
        JsonNode pushedRow = findBy(pushed, glPushed.getStatementNo());
        assertNotNull(pushedRow.get("companyName"), "公司主体名投影");
        assertTrue(pushedRow.get("bankAccount").asText().contains("****"), "账号脱敏展示");
        assertEquals(1, pushedRow.get("statementCount").asInt(), "一期 1 笔流水 → 1 张凭证");
        assertEquals("GL-" + UNIQUE_SUFFIX, pushedRow.get("voucherNo").asText());
    }

    /**
     * W3 修复端到端（2026-09-18）：他公司主体的 PENDING 草稿此前在凭证中心不可见、
     * 复核 404；现在 cross-company 用户（admin）跨公司可见且可批量复核。
     */
    @Test
    void crossCompanyAdminSeesAndReviewsOtherCompanyDraft() throws Exception {
        String token = login();
        long accountId = createAccount(token, "跨公司草稿账户-" + UNIQUE_SUFFIX);
        Long otherCompanyId = insertCompany("KVGCRS").getId();

        StatementRecord otherDraft = insertStatementForCompany(otherCompanyId, accountId, "PENDING", null, null, null,
                "他公司待复核草稿 " + UNIQUE_SUFFIX);

        // 1. DRAFT 桶可见他公司草稿（唯一关键词精确命中，防共享 H2 分页溢出）
        JsonNode draft = listGroups(token, "DRAFT", otherDraft.getStatementNo());
        assertEquals(1, countBy(draft, otherDraft.getStatementNo()), "cross-company 用户 DRAFT 桶可见他公司草稿");

        // 2. 批量复核他公司草稿（此前 require 锁本公司 → 404）
        MvcResult review = mockMvc.perform(post("/api/statements/batch-review")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + otherDraft.getId() + "],\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode reviewData = objectMapper.readTree(review.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("data");
        assertEquals(1, reviewData.get("successCount").asInt(), "他公司草稿复核成功");
        assertEquals("APPROVED", reviewData.get("rows").get(0).get("outcome").asText());

        // 3. 复核后落入待推送桶（PENDING 桶 = APPROVED 未推送）
        JsonNode pendingBucket = listGroups(token, "PENDING", otherDraft.getStatementNo());
        assertEquals(1, countBy(pendingBucket, otherDraft.getStatementNo()), "复核后进入待推送桶");
        assertEquals(0, countBy(listGroups(token, "DRAFT", otherDraft.getStatementNo()), otherDraft.getStatementNo()));
    }

    /** W3 隔离保持：无 cross-company 权限的用户仍只看本公司行（Service 层直调，绕过端点权限门）。 */
    @Test
    void viewerWithoutCrossCompanyPermissionSeesOwnCompanyOnly() {
        Company ownCompany = insertCompany("W3OWN");
        Company otherCompany = insertCompany("W3OTH");
        Long viewerId = insertUser(ownCompany.getId(), "w3-viewer", 4L);

        long accountId = insertAccountForCompany(ownCompany.getId());
        StatementRecord ownRow = insertStatementForCompany(ownCompany.getId(), accountId, "PENDING", null,
                null, null, "本公司草稿 " + UNIQUE_SUFFIX);
        StatementRecord otherRow = insertStatementForCompany(otherCompany.getId(), accountId, "PENDING", null,
                null, null, "他公司草稿 " + UNIQUE_SUFFIX);

        PageResponse<VoucherGroupResponse> page = voucherGroupService.pageGroups(1, 50, "ALL", null, viewerId);
        long ownHits = page.records().stream()
                .filter(r -> ownRow.getStatementNo().equals(r.statementNo())).count();
        long otherHits = page.records().stream()
                .filter(r -> otherRow.getStatementNo().equals(r.statementNo())).count();
        assertEquals(1, ownHits, "本公司行可见");
        assertEquals(0, otherHits, "无 cross-company 权限用户看不到他公司行");
    }

    private Company insertCompany(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Company company = new Company();
        company.setCode(prefix + "_" + suffix);
        company.setName(prefix + " company " + suffix);
        company.setStatus("ACTIVE");
        companyMapper.insert(company);
        return company;
    }

    private Long insertUser(Long companyId, String username, Long roleId) {
        SysUser user = new SysUser();
        user.setCompanyId(companyId);
        user.setUsername(username + "-" + UNIQUE_SUFFIX);
        user.setPasswordHash("$2a$10$qa-test-only-not-a-real-hash-not-used-for-login-in-this-class");
        user.setEmail(username + "-" + UNIQUE_SUFFIX + "@qa.finflow.local");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        if (roleId != null) {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(user.getId());
            userRole.setRoleId(roleId);
            userRoleMapper.insert(userRole);
        }
        return user.getId();
    }

    private long insertAccountForCompany(Long companyId) {
        BankAccount account = new BankAccount();
        account.setCompanyId(companyId);
        account.setBankCode("CITIC");
        account.setAccountName("W3 隔离测试账户 " + UNIQUE_SUFFIX);
        account.setAccountNumber("6222" + UNIQUE_SUFFIX + "99");
        account.setCurrency("CNY");
        account.setAvailableBalance(new BigDecimal("1000.00"));
        account.setStatus("ACTIVE");
        bankAccountMapper.insert(account);
        return account.getId();
    }

    private int countBy(JsonNode page, String statementNo) {
        int hits = 0;
        for (JsonNode row : page.get("records")) {
            if (statementNo.equals(row.get("statementNo").asText())) {
                hits++;
            }
        }
        return hits;
    }

    private JsonNode findBy(JsonNode page, String statementNo) {
        for (JsonNode row : page.get("records")) {
            if (statementNo.equals(row.get("statementNo").asText())) {
                return row;
            }
        }
        throw new AssertionError("row not found: " + statementNo);
    }

    private JsonNode listGroups(String token, String status, String keyword) throws Exception {
        StringBuilder url = new StringBuilder("/api/statements/voucher-groups?status=" + status);
        if (keyword != null) {
            url.append("&keyword=").append(keyword);
        }
        MvcResult result = mockMvc.perform(get(url.toString())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private StatementRecord insertStatement(Long accountId, String reviewStatus, String pushStatus,
                                            String voucherNo, String pushMessage, String summary) {
        return insertStatementForCompany(1L, accountId, reviewStatus, pushStatus, voucherNo, pushMessage, summary);
    }

    private StatementRecord insertStatementForCompany(Long companyId, Long accountId, String reviewStatus,
                                                      String pushStatus, String voucherNo, String pushMessage,
                                                      String summary) {
        // statement_record.batch_id NOT NULL + FK → 先建真实导入批次（与规则引擎 QA fixture 同模式）
        StatementImportBatch batch = new StatementImportBatch();
        batch.setCompanyId(companyId);
        batch.setBatchNo("KVG-BATCH-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        batch.setSourceType("MANUAL");
        batch.setStatus("COMPLETED");
        batchMapper.insert(batch);

        StatementRecord statement = new StatementRecord();
        statement.setCompanyId(companyId);
        statement.setBatchId(batch.getId());
        statement.setStatementNo("KVG-" + UNIQUE_SUFFIX + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        statement.setBankAccountId(accountId);
        statement.setTransactionTime(LocalDateTime.parse("2026-09-17T09:30:00"));
        statement.setDirection("EXPENSE");
        statement.setAmount(new BigDecimal("128.50"));
        statement.setCurrency("CNY");
        statement.setCounterpartyName("凭证中心QA对手方");
        statement.setRawPayload("{\"qa\":\"voucher-group fixture\"}");
        statement.setSummary(summary);
        statement.setValidationStatus("PASSED");
        statement.setReviewStatus(reviewStatus);
        statement.setPushStatus(pushStatus);
        statement.setVoucherNo(voucherNo);
        statement.setPushMessage(pushMessage);
        statementRecordMapper.insert(statement);
        return statement;
    }

    private long createAccount(String token, String accountName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/bank-accounts")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\"CITIC\",\"accountName\":\"" + accountName + "\","
                                + "\"accountNumber\":\"6222" + UNIQUE_SUFFIX + "0001\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("data").get("id").asLong();
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data").get("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
