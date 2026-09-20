package com.finance.system.statement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.domain.entity.StatementAuditEvent;
import com.finance.system.domain.entity.StatementImportBatch;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.mapper.AiProviderConfigMapper;
import com.finance.system.domain.mapper.StatementAuditEventMapper;
import com.finance.system.domain.mapper.StatementImportBatchMapper;
import com.finance.system.domain.mapper.StatementRecordMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V33 凭证草稿详情全链路（独立 Spring 上下文，JDK HttpServer 冒充 LLM 端点）：
 * <ul>
 *   <li>AI 建议含 entries 分录 → POST /statements/{id}/ai-suggestion 落库 ai_suggestion_json；</li>
 *   <li>GET /statements/{id} 返回结构化 aiSuggestion（分录+逐行置信度+balanced）；</li>
 *   <li>PUT /statements/{id}/voucher-draft 保存人工修正：回写分录（edited 标记）+ 主摘要同步
 *       statement.summary（金蝶 FREMARK/FCOMMENT 口径）+ 审计 VOUCHER_DRAFT_EDIT；</li>
 *   <li>护栏：借贷不平衡 400、行数据缺失 400、已推送 409。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class VoucherDraftIntegrationTest {

    private static final String PASSWORD = "Test@12345";
    private static final String API_KEY = "draft-key-789";

    private static HttpServer mockLlm;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AiProviderConfigMapper configMapper;
    @Autowired
    private StatementRecordMapper statementMapper;
    @Autowired
    private StatementImportBatchMapper batchMapper;
    @Autowired
    private StatementAuditEventMapper auditMapper;

    @AfterEach
    void cleanConfigRow() {
        configMapper.deleteById(1L);
    }

    @AfterAll
    static void stopMock() {
        if (mockLlm != null) {
            mockLlm.stop(0);
        }
    }

    private static void startMockIfAbsent() throws IOException {
        if (mockLlm != null) {
            return;
        }
        mockLlm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockLlm.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!("Bearer " + API_KEY).equals(auth)) {
                respond(exchange, 401, "{\"error\":{\"message\":\"bad key\"}}");
                return;
            }
            respond(exchange, 200, """
                    {"model":"mock-model","choices":[{"message":{"role":"assistant","content":\
                    "{\\"businessCategory\\":\\"货款收入\\",\\"suggestedSummary\\":\\"收泰拉贸易行货款\\",\
                    \\"counterpartyType\\":\\"CUSTOMER\\",\\"settlementMethod\\":\\"银行转账\\",\
                    \\"suggestedSubject\\":\\"应收账款\\",\\"riskNotes\\":\\"无\\",\\"confidence\\":0.85,\
                    \\"rationale\\":\\"对手方为客户\\",\
                    \\"entries\\":[{\\"summary\\":\\"收泰拉贸易行货款\\",\\"subjectCode\\":\\"\\",\
                    \\"subjectName\\":\\"银行存款\\",\\"direction\\":\\"DEBIT\\",\\"amount\\":12800.00,\\"confidence\\":1.0},\
                    {\\"summary\\":\\"收泰拉贸易行货款\\",\\"subjectCode\\":\\"1122\\",\
                    \\"subjectName\\":\\"应收账款\\",\\"direction\\":\\"CREDIT\\",\\"amount\\":12800.00,\\"confidence\\":0.85}]}"},\
                    "finish_reason":"stop"}],\
                    "usage":{"prompt_tokens":90,"completion_tokens":70,"total_tokens":160}}""");
        });
        mockLlm.start();
    }

    @Test
    void aiSuggestionWithEntriesPersistsAndDetailExposesIt() throws Exception {
        startMockIfAbsent();
        String token = login("admin", "Admin@123");
        saveEnabledConfig(token);
        long id = seedStatement("VDFT-" + suffix());

        mockMvc.perform(post("/api/statements/" + id + "/ai-suggestion")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        MvcResult detail = mockMvc.perform(get("/api/statements/" + id)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        var data = objectMapper.readTree(detail.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data");
        assertNotNull(data.get("aiSuggestion"), "aiSuggestion must be exposed on detail");
        var suggestion = data.get("aiSuggestion");
        assertEquals(2, suggestion.get("entries").size());
        assertEquals("银行存款", suggestion.get("entries").get(0).get("subjectName").asText());
        assertEquals("DEBIT", suggestion.get("entries").get(0).get("direction").asText());
        assertEquals(0.85, suggestion.get("entries").get(1).get("confidence").asDouble(), 1e-9);
        assertEquals(true, suggestion.get("balanced").asBoolean());
        assertEquals(false, suggestion.get("edited").asBoolean());

        // 审计：AI_SUGGESTION_REFRESH
        assertTrue(auditMapper.selectCount(new LambdaQueryWrapper<StatementAuditEvent>()
                .eq(StatementAuditEvent::getStatementId, id)
                .eq(StatementAuditEvent::getAction, "AI_SUGGESTION_REFRESH")) >= 1);
    }

    @Test
    void humanCorrectionRewritesEntriesSummaryAndAudit() throws Exception {
        startMockIfAbsent();
        String token = login("admin", "Admin@123");
        saveEnabledConfig(token);
        long id = seedStatement("VDHM-" + suffix());

        mockMvc.perform(post("/api/statements/" + id + "/ai-suggestion")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        MvcResult saved = mockMvc.perform(put("/api/statements/" + id + "/voucher-draft")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"summary":"收罗德岛货款-人工修正",\
                                "entries":[{"summary":"收罗德岛货款","subjectCode":"","subjectName":"银行存款",\
                                "direction":"DEBIT","amount":12800.00,"confidence":1.0},\
                                {"summary":"收罗德岛货款","subjectCode":"1122","subjectName":"应收账款-罗德岛",\
                                "direction":"CREDIT","amount":12800.00,"confidence":0.9}]}"""))
                .andExpect(status().isOk())
                .andReturn();
        var doc = objectMapper.readTree(saved.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data");
        assertEquals(true, doc.get("edited").asBoolean());
        assertEquals("应收账款-罗德岛", doc.get("entries").get(1).get("subjectName").asText());

        // 主摘要已同步 statement.summary（金蝶单据备注口径），复核意见带「已人工修正」
        StatementRecord record = statementMapper.selectById(id);
        assertEquals("收罗德岛货款-人工修正", record.getSummary());
        assertTrue(record.getReviewComment().contains("已人工修正"));

        // 详情里 edited 标记 + 审计 VOUCHER_DRAFT_EDIT
        MvcResult detail = mockMvc.perform(get("/api/statements/" + id)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        var suggestion = objectMapper.readTree(detail.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data").get("aiSuggestion");
        assertEquals(true, suggestion.get("edited").asBoolean());
        assertTrue(auditMapper.selectCount(new LambdaQueryWrapper<StatementAuditEvent>()
                .eq(StatementAuditEvent::getStatementId, id)
                .eq(StatementAuditEvent::getAction, "VOUCHER_DRAFT_EDIT")) >= 1);
    }

    @Test
    void unbalancedOrInvalidEntriesAreRejected() throws Exception {
        startMockIfAbsent();
        String token = login("admin", "Admin@123");
        saveEnabledConfig(token);
        long id = seedStatement("VDBAD-" + suffix());

        mockMvc.perform(put("/api/statements/" + id + "/voucher-draft")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[{"summary":"a","subjectName":"银行存款","direction":"DEBIT",\
                                "amount":12800.00},{"summary":"b","subjectName":"应收账款","direction":"CREDIT",\
                                "amount":12000.00}]}"""))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/statements/" + id + "/voucher-draft")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[{"summary":"a","subjectName":"","direction":"DEBIT",\
                                "amount":12800.00}]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pushedStatementCannotBeEdited() throws Exception {
        startMockIfAbsent();
        String token = login("admin", "Admin@123");
        saveEnabledConfig(token);
        long id = seedStatement("VDPUSH-" + suffix());
        StatementRecord record = statementMapper.selectById(id);
        record.setPushStatus("PUSHED");
        record.setVoucherNo("KD-MOCK-X");
        statementMapper.updateById(record);

        mockMvc.perform(put("/api/statements/" + id + "/voucher-draft")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[{"summary":"a","subjectName":"银行存款","direction":"DEBIT",\
                                "amount":12800.00},{"summary":"b","subjectName":"应收账款","direction":"CREDIT",\
                                "amount":12800.00}]}"""))
                .andExpect(status().isConflict());
    }

    /** W10（V39）：撤回未推送凭证 → WITHDRAWN + 撤回时间/人落库 + 审计 WITHDRAW；记录保留可追溯。 */
    @Test
    void withdrawMarksRecordAndKeepsItForTrace() throws Exception {
        String token = login("admin", "Admin@123");
        long id = seedStatement("WD-" + suffix());

        MvcResult res = mockMvc.perform(post("/api/statements/" + id + "/withdraw")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("WITHDRAWN",
                objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .get("data").get("reviewStatus").asText());

        StatementRecord record = statementMapper.selectById(id);
        assertEquals("WITHDRAWN", record.getReviewStatus());
        assertNotNull(record.getWithdrawnAt(), "撤回时间必须落库（追溯）");
        assertNotNull(record.getWithdrawnBy(), "撤回人必须落库（追溯）");
        assertNotNull(record.getStatementNo(), "记录与流水号保留（唯一约束下不产生重复行）");

        assertTrue(auditMapper.selectCount(new LambdaQueryWrapper<StatementAuditEvent>()
                .eq(StatementAuditEvent::getStatementId, id)
                .eq(StatementAuditEvent::getAction, "WITHDRAW")) >= 1, "审计须落 WITHDRAW");
    }

    /** W10（V39）：已推送金蝶不可撤回（409）；重复撤回同样 409。 */
    @Test
    void pushedOrAlreadyWithdrawnCannotBeWithdrawn() throws Exception {
        String token = login("admin", "Admin@123");
        long pushed = seedStatement("WDP-" + suffix());
        statementMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<StatementRecord>()
                        .set(StatementRecord::getPushStatus, "GL_PUSHED")
                        .eq(StatementRecord::getId, pushed));
        mockMvc.perform(post("/api/statements/" + pushed + "/withdraw")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict());

        long fresh = seedStatement("WD2-" + suffix());
        mockMvc.perform(post("/api/statements/" + fresh + "/withdraw")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/statements/" + fresh + "/withdraw")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict());
    }

    // ---- helpers ----

    private void saveEnabledConfig(String token) throws Exception {        configMapper.deleteById(1L);
        mockMvc.perform(put("/api/ai/config")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"baseUrl":"http://127.0.0.1:%d","apiKey":"%s",\
                                "model":"mock-model",\
                                "capabilities":{"self-test":true,"accounting-suggestion":true}}"""
                                .formatted(mockLlm.getAddress().getPort(), API_KEY)))
                .andExpect(status().isOk());
    }

    private long seedStatement(String statementNo) {
        StatementImportBatch batch = new StatementImportBatch();
        batch.setCompanyId(1L);
        batch.setBatchNo("BT-" + suffix());
        batch.setSourceType("FILE");
        batch.setStatus("COMPLETED");
        batch.setTotalCount(1);
        batch.setImportedCount(1);
        batch.setCreatedAt(LocalDateTime.now());
        batchMapper.insert(batch);

        StatementRecord record = new StatementRecord();
        record.setCompanyId(1L);
        record.setBatchId(batch.getId());
        record.setStatementNo(statementNo);
        record.setTransactionTime(LocalDateTime.now().minusHours(2));
        record.setDirection("CREDIT");
        record.setAmount(new BigDecimal("12800.00"));
        record.setCurrency("CNY");
        record.setCounterpartyName("泰拉贸易行");
        record.setCounterpartyAccount("6222****0011");
        record.setSummary("货款");
        record.setRawPayload("{}");
        record.setValidationStatus("PASSED");
        record.setReviewStatus("PENDING");
        record.setPushStatus("NOT_PUSHED");
        statementMapper.insert(record);
        return record.getId();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data").get("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
