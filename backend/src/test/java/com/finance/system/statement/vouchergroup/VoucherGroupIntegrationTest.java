package com.finance.system.statement.vouchergroup;

import com.finance.system.domain.entity.BankAccount;
import com.finance.system.domain.entity.StatementImportBatch;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.mapper.BankAccountMapper;
import com.finance.system.domain.mapper.StatementImportBatchMapper;
import com.finance.system.domain.mapper.StatementRecordMapper;
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
 *  - 待推送桶 = APPROVED 且未推送；关键词命中流水号/摘要；公司隔离（他公司行不可见）。
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

        // 他公司行（company 2）必须被公司隔离过滤掉
        insertStatementForCompany(2L, accountId, "APPROVED", "PUSHED", "OTHER-" + UNIQUE_SUFFIX, null, "他公司行");

        // 1. ALL 桶：全部可见行（5 行本公司 + 关键词无关），他公司行不出现
        JsonNode all = listGroups(token, "ALL", null);
        assertTrue(countBy(all, pending.getStatementNo()) == 1, "待复核行在 ALL 桶可见");
        assertTrue(countBy(all, toPush.getStatementNo()) == 1, "待推送行在 ALL 桶可见");
        assertTrue(countBy(all, glPushed.getStatementNo()) == 1);
        assertTrue(countBy(all, legacyPushed.getStatementNo()) == 1);
        assertTrue(countBy(all, glFailed.getStatementNo()) == 1);
        assertTrue(countBy(all, "OTHER-" + UNIQUE_SUFFIX) == 0, "他公司行必须被公司隔离过滤");
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
