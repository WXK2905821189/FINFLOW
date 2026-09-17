package com.finance.system.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.domain.entity.BankDataBalance;
import com.finance.system.domain.entity.BankDataRawMessage;
import com.finance.system.domain.entity.BankDataStatement;
import com.finance.system.domain.entity.BankDataSyncTask;
import com.finance.system.domain.mapper.BankDataBalanceMapper;
import com.finance.system.domain.mapper.BankDataRawMessageMapper;
import com.finance.system.domain.mapper.BankDataStatementMapper;
import com.finance.system.domain.mapper.BankDataSyncTaskMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The company-archive filing drawer had no backend at all: company rows were seeded
 * by migration only and bank accounts were welded to the creator's company. This
 * test locks the new archive contract - create/rename a company, re-file an account
 * into it, and above all the historical backfill: balance and statement rows
 * denormalize company_id, so re-filing must rewrite them in the same transaction,
 * otherwise cross-company filters keep the account under its old company forever.
 *
 * <p>Everything is created through the API (company + account + rows) instead of
 * relying on V1 seeds - the shared in-memory H2 is mutated by other test classes,
 * so seeding assumptions proved fragile.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CompanyArchiveIntegrationTest {

    private static final String UNIQUE_SUFFIX = UUID.randomUUID().toString().substring(0, 8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BankDataBalanceMapper balanceMapper;

    @Autowired
    private BankDataStatementMapper statementMapper;

    @Autowired
    private BankDataSyncTaskMapper taskMapper;

    @Autowired
    private BankDataRawMessageMapper rawMessageMapper;

    @Test
    void archiveCreateRenameRefileAndHistoricalBackfill() throws Exception {
        String token = login();
        String companyName = "档案测试分公司-" + UNIQUE_SUFFIX;

        // 0. create a dedicated account through the API (admin's own company)
        long accountId = createAccount(token, "档案测试账户-" + UNIQUE_SUFFIX);

        // 1. create archive
        MvcResult created = mockMvc.perform(post("/api/bank-account-archive/companies")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + companyName + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode company = objectMapper.readTree(created.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("data");
        long companyId = company.get("id").asLong();
        assertTrue(companyId > 1, "archive company should get a fresh id");
        assertEquals(companyName, company.get("name").asText());
        assertEquals("ACTIVE", company.get("status").asText());

        // 2. duplicate name is rejected
        mockMvc.perform(post("/api/bank-account-archive/companies")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + companyName + "\"}"))
                .andExpect(status().isBadRequest());

        // 3. rename with the same name is a no-op success (uniqueness excludes self)
        mockMvc.perform(put("/api/bank-account-archive/companies/" + companyId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + companyName + "\"}"))
                .andExpect(status().isOk());
        assertEquals(companyName, company.get("name").asText());

        // 4. re-file the dedicated account and expect history to follow
        BankDataSyncTask task = new BankDataSyncTask();
        task.setCompanyId(1L);
        task.setTaskNo("ARCH-T-" + UNIQUE_SUFFIX);
        task.setAdapterCode("CMB");
        task.setBankAccountId(accountId);
        task.setRequestId("arch-req-" + UNIQUE_SUFFIX);
        task.setStatus("SUCCEEDED");
        taskMapper.insert(task);

        BankDataRawMessage raw = new BankDataRawMessage();
        raw.setCompanyId(1L);
        raw.setTaskId(task.getId());
        raw.setAdapterCode("CMB");
        raw.setContentSha256(UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""));
        raw.setPayload("{}");
        raw.setReceivedAt(LocalDateTime.now());
        raw.setRetentionUntil(LocalDateTime.now().plusYears(1));
        rawMessageMapper.insert(raw);

        BankDataBalance balance = new BankDataBalance();
        balance.setCompanyId(1L);
        balance.setTaskId(task.getId());
        balance.setRawMessageId(raw.getId());
        balance.setBankAccountId(accountId);
        balance.setBankRequestNo("ARCH-B-" + UNIQUE_SUFFIX);
        balance.setAvailableBalance(new BigDecimal("1.00"));
        balance.setCurrency("CNY");
        balance.setAsOfTime(LocalDateTime.now());
        balance.setValidationStatus("VALID");
        balanceMapper.insert(balance);

        BankDataStatement statement = new BankDataStatement();
        statement.setCompanyId(1L);
        statement.setTaskId(task.getId());
        statement.setRawMessageId(raw.getId());
        statement.setBankAccountId(accountId);
        statement.setStatementNo("ARCH-S-" + UNIQUE_SUFFIX);
        statement.setTransactionTime(LocalDateTime.now());
        statement.setDirection("D");
        statement.setAmount(new BigDecimal("5.00"));
        statement.setCurrency("CNY");
        statement.setSummary("archive backfill test");
        statement.setValidationStatus("VALID");
        statementMapper.insert(statement);

        mockMvc.perform(put("/api/bank-account-archive/accounts/" + accountId + "/company")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\":" + companyId + "}"))
                .andExpect(status().isOk());

        assertEquals(companyId, balanceMapper.selectById(balance.getId()).getCompanyId(),
                "historical balance rows must follow the re-file");
        assertEquals(companyId, statementMapper.selectById(statement.getId()).getCompanyId(),
                "historical statement rows must follow the re-file");

        // 5. archive view shows the new company with the account filed under it
        MvcResult view = mockMvc.perform(get("/api/bank-account-archive")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode viewData = objectMapper.readTree(view.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("data");
        assertTrue(viewData.get("companies").toString().contains(companyName));
        JsonNode filedAccount = null;
        for (JsonNode row : viewData.get("accounts")) {
            if (row.get("id").asLong() == accountId) {
                filedAccount = row;
            }
        }
        assertNotNull(filedAccount, "created account must appear in the archive view");
        assertEquals(companyId, filedAccount.get("companyId").asLong());

        // 6. re-filing into an unknown archive is a 404, not a silent write
        mockMvc.perform(put("/api/bank-account-archive/accounts/" + accountId + "/company")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\":999999}"))
                .andExpect(status().isNotFound());
    }

    /**
     * V32 soft delete: DELETE removes the account from the archive board and the
     * bank-accounts list (both are MyBatis-Plus queries, automatically filtered by
     * @TableLogic) while the historical statement/balance rows stay queryable in H2.
     */
    @Test
    void archiveSoftDeleteHidesAccountButKeepsHistory() throws Exception {
        String token = login();
        long accountId = createAccount(token, "软删测试账户-" + UNIQUE_SUFFIX);

        BankDataSyncTask task = new BankDataSyncTask();
        task.setCompanyId(1L);
        task.setTaskNo("DEL-T-" + UNIQUE_SUFFIX);
        task.setAdapterCode("CMB");
        task.setBankAccountId(accountId);
        task.setRequestId("del-req-" + UNIQUE_SUFFIX);
        task.setStatus("SUCCEEDED");
        taskMapper.insert(task);

        BankDataRawMessage raw = new BankDataRawMessage();
        raw.setCompanyId(1L);
        raw.setTaskId(task.getId());
        raw.setAdapterCode("CMB");
        raw.setContentSha256(UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""));
        raw.setPayload("{}");
        raw.setReceivedAt(LocalDateTime.now());
        raw.setRetentionUntil(LocalDateTime.now().plusYears(1));
        rawMessageMapper.insert(raw);

        BankDataStatement statement = new BankDataStatement();
        statement.setCompanyId(1L);
        statement.setTaskId(task.getId());
        statement.setRawMessageId(raw.getId());
        statement.setBankAccountId(accountId);
        statement.setStatementNo("DEL-S-" + UNIQUE_SUFFIX);
        statement.setTransactionTime(LocalDateTime.now());
        statement.setDirection("D");
        statement.setAmount(new BigDecimal("3.00"));
        statement.setCurrency("CNY");
        statement.setSummary("soft delete keeps history");
        statement.setValidationStatus("VALID");
        statementMapper.insert(statement);

        // delete via the new endpoint (bank:manage)
        mockMvc.perform(delete("/api/bank-accounts/" + accountId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        // account disappears from both surfaces (logical-delete filter, not a hard DELETE)
        String accountList = mockMvc.perform(get("/api/bank-accounts")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(!accountList.contains("软删测试账户-" + UNIQUE_SUFFIX),
                "deleted account must vanish from the accounts list");

        String view = mockMvc.perform(get("/api/bank-account-archive")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(!view.contains("软删测试账户-" + UNIQUE_SUFFIX),
                "deleted account must vanish from the archive board");

        // history is retained: raw mapper read (bypasses @TableLogic) still finds the row
        BankDataStatement history = statementMapper.selectById(statement.getId());
        assertNotNull(history, "historical statement rows must be retained");
        assertEquals(accountId, history.getBankAccountId());

        // deleting again hits the logical-delete filter → 404
        mockMvc.perform(delete("/api/bank-accounts/" + accountId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    /**
     * Unassign (2026-09-17): the archive board keeps a permanent "未归属" zone and an
     * account can be dragged back into it. Only bank_account.company_id resets to NULL
     * (explicit UpdateWrapper — updateById skips null fields); historical balance/
     * statement rows keep their original company_id (NOT NULL constraint, and history
     * must stay queryable under the old scope). Re-filing afterwards works like before.
     */
    @Test
    void archiveUnassignResetsAccountAndHistory() throws Exception {
        String token = login();
        String companyName = "未归属测试公司-" + UNIQUE_SUFFIX;
        long accountId = createUnassignAccount(token, "未归属测试账户-" + UNIQUE_SUFFIX);

        // file the account into a fresh company, history follows
        MvcResult created = mockMvc.perform(post("/api/bank-account-archive/companies")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + companyName + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        long companyId = objectMapper.readTree(created.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("data").get("id").asLong();

        BankDataSyncTask task = new BankDataSyncTask();
        task.setCompanyId(1L);
        task.setTaskNo("UNA-T-" + UNIQUE_SUFFIX);
        task.setAdapterCode("CMB");
        task.setBankAccountId(accountId);
        task.setRequestId("una-req-" + UNIQUE_SUFFIX);
        task.setStatus("SUCCEEDED");
        taskMapper.insert(task);

        BankDataRawMessage raw = new BankDataRawMessage();
        raw.setCompanyId(1L);
        raw.setTaskId(task.getId());
        raw.setAdapterCode("CMB");
        raw.setContentSha256(UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""));
        raw.setPayload("{}");
        raw.setReceivedAt(LocalDateTime.now());
        raw.setRetentionUntil(LocalDateTime.now().plusYears(1));
        rawMessageMapper.insert(raw);

        BankDataBalance balance = new BankDataBalance();
        balance.setCompanyId(1L);
        balance.setTaskId(task.getId());
        balance.setRawMessageId(raw.getId());
        balance.setBankAccountId(accountId);
        balance.setBankRequestNo("UNA-B-" + UNIQUE_SUFFIX);
        balance.setAvailableBalance(new BigDecimal("2.00"));
        balance.setCurrency("CNY");
        balance.setAsOfTime(LocalDateTime.now());
        balance.setValidationStatus("VALID");
        balanceMapper.insert(balance);

        BankDataStatement statement = new BankDataStatement();
        statement.setCompanyId(1L);
        statement.setTaskId(task.getId());
        statement.setRawMessageId(raw.getId());
        statement.setBankAccountId(accountId);
        statement.setStatementNo("UNA-S-" + UNIQUE_SUFFIX);
        statement.setTransactionTime(LocalDateTime.now());
        statement.setDirection("D");
        statement.setAmount(new BigDecimal("4.00"));
        statement.setCurrency("CNY");
        statement.setSummary("unassign resets history scope");
        statement.setValidationStatus("VALID");
        statementMapper.insert(statement);

        mockMvc.perform(put("/api/bank-account-archive/accounts/" + accountId + "/company")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\":" + companyId + "}"))
                .andExpect(status().isOk());
        assertEquals(companyId, balanceMapper.selectById(balance.getId()).getCompanyId());

        // unassign → account loses company scope, history keeps its original scope
        mockMvc.perform(post("/api/bank-account-archive/accounts/" + accountId + "/unassign")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        assertEquals(companyId, balanceMapper.selectById(balance.getId()).getCompanyId(),
                "historical balance rows keep their original company scope on unassign");
        assertEquals(companyId, statementMapper.selectById(statement.getId()).getCompanyId(),
                "historical statement rows keep their original company scope on unassign");

        // archive view reports the account with companyId = null (未归属 zone)
        String view = mockMvc.perform(get("/api/bank-account-archive")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(view.contains("\"accountId\":" + accountId) || view.contains("\"id\":" + accountId),
                "unassigned account must still appear on the archive board");
        MvcResult viewAfter = mockMvc.perform(get("/api/bank-account-archive")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode unassignedAccount = null;
        for (JsonNode row : objectMapper.readTree(viewAfter.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("data").get("accounts")) {
            if (row.get("id").asLong() == accountId) {
                unassignedAccount = row;
            }
        }
        assertNotNull(unassignedAccount, "unassigned account must appear in the archive view");
        assertTrue(unassignedAccount.get("companyId").isNull(),
                "unassigned account must report companyId = null");

        // idempotent: unassigning an already-unassigned account is a no-op success
        mockMvc.perform(post("/api/bank-account-archive/accounts/" + accountId + "/unassign")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        // re-file after unassign behaves like a fresh filing (history follows again)
        mockMvc.perform(put("/api/bank-account-archive/accounts/" + accountId + "/company")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\":" + companyId + "}"))
                .andExpect(status().isOk());
        assertEquals(companyId, balanceMapper.selectById(balance.getId()).getCompanyId(),
                "re-filing after unassign must re-scope the historical rows");
    }

    private long createUnassignAccount(String token, String accountName) throws Exception {
        String body = "{\"bankCode\":\"CITIC\",\"accountName\":\"" + accountName + "\","
                + "\"accountNumber\":\"6222" + UNIQUE_SUFFIX.replaceAll("\\D", "7") + "0002\","
                + "\"currency\":\"CNY\",\"availableBalance\":0,\"status\":\"ACTIVE\"}";
        MvcResult result = mockMvc.perform(post("/api/bank-accounts")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("data").get("id").asLong();
    }

    private long createAccount(String token, String accountName) throws Exception {
        String body = "{\"bankCode\":\"CITIC\",\"accountName\":\"" + accountName + "\","
                + "\"accountNumber\":\"6222" + UNIQUE_SUFFIX.replaceAll("\\D", "7") + "0001\","
                + "\"currency\":\"CNY\",\"availableBalance\":0,\"status\":\"ACTIVE\"}";
        MvcResult result = mockMvc.perform(post("/api/bank-accounts")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
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
