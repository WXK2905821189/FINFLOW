package com.finance.system.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.domain.entity.AiCallLog;
import com.finance.system.domain.entity.AiProviderConfig;
import com.finance.system.domain.entity.StatementImportBatch;
import com.finance.system.domain.entity.StatementRecord;
import com.finance.system.domain.mapper.AiCallLogMapper;
import com.finance.system.domain.mapper.AiProviderConfigMapper;
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
 * V28 在线配置 + A1 智能入账建议 全链路（独立 Spring 上下文，JDK HttpServer 冒充
 * OpenAI 兼容端点）。AI 的启用完全走 DB 在线配置（不借 env），契约：
 * <ul>
 *   <li>PUT /api/ai/config 保存即生效：自检无需重启即用上 DB 里的 base-url/密钥；</li>
 *   <li>密钥永不回显（只有 hint 尾 4 位与"是否已配置"），明文不出现在任何响应；</li>
 *   <li>启用但无密钥（env 未提供且未填写）保存被 400 护栏拒绝；</li>
 *   <li>A1：返回结构化建议并落审计；模型未按约定 JSON 返回 → 502；跨公司流水 404。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiConfigAndSuggestionIntegrationTest {

    private static final String PASSWORD = "Test@12345";
    private static final String API_KEY = "cfg-key-456";

    private static HttpServer mockLlm;
    private static int mockPort;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AiProviderConfigMapper configMapper;
    @Autowired
    private AiCallLogMapper callLogMapper;
    @Autowired
    private StatementRecordMapper statementMapper;
    @Autowired
    private StatementImportBatchMapper batchMapper;

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
            byte[] body = exchange.getRequestBody().readAllBytes();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!("Bearer " + API_KEY).equals(auth)) {
                respond(exchange, 401, "{\"error\":{\"message\":\"bad key\"}}");
                return;
            }
            String request = new String(body, StandardCharsets.UTF_8);
            if (request.contains("BAD_JSON")) {
                respond(exchange, 200, """
                        {"choices":[{"message":{"role":"assistant",\
                        "content":"抱歉，我无法以 JSON 形式回答"}}]}""");
                return;
            }
            if (request.contains("流水信息")) {
                respond(exchange, 200, """
                        {"model":"mock-model","choices":[{"message":{"role":"assistant","content":\
                        "{\\"businessCategory\\":\\"货款收入\\",\\"suggestedSummary\\":\\"收XX公司货款\\",\
                        \\"counterpartyType\\":\\"CUSTOMER\\",\\"settlementMethod\\":\\"银行转账\\",\
                        \\"suggestedSubject\\":\\"应收账款\\",\\"riskNotes\\":\\"无\\",\\"confidence\\":0.85,\
                        \\"rationale\\":\\">对手方为客户且金额整数\\"}"},"finish_reason":"stop"}],\
                        "usage":{"prompt_tokens":88,"completion_tokens":66,"total_tokens":154}}""");
                return;
            }
            respond(exchange, 200, """
                    {"model":"mock-model","choices":[{"message":{"role":"assistant",\
                    "content":"PONG"},"finish_reason":"stop"}],\
                    "usage":{"prompt_tokens":21,"completion_tokens":2,"total_tokens":23}}""");
        });
        mockLlm.start();
        mockPort = mockLlm.getAddress().getPort();
    }

    @Test
    void configSaveTakesEffectImmediatelyWithoutRestart() throws Exception {
        startMockIfAbsent();
        String token = login("admin", "Admin@123");

        // 1. 默认（纯 env，无 DB 行）：禁用 → 自检 403
        mockMvc.perform(post("/api/ai/self-test").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());

        // 2. 保存 DB 在线配置（启用 + mock 端点 + 密钥 + 能力开关）——保存即生效
        MvcResult saved = mockMvc.perform(put("/api/ai/config")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"baseUrl":"http://127.0.0.1:%d","apiKey":"%s",\
                                "model":"mock-model","dailyLimitPerUser":9,\
                                "capabilities":{"self-test":true,"accounting-suggestion":true}}"""
                                .formatted(mockPort, API_KEY)))
                .andExpect(status().isOk())
                .andReturn();
        String savedBody = saved.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(savedBody.contains("\"configSource\":\"在线配置\""), "effective source switches to DB");
        assertTrue(savedBody.contains("\"apiKeyConfigured\":true"), "key presence exposed");
        assertTrue(savedBody.contains("456"), "key hint (last 4) is exposed");
        assertTrue(!savedBody.contains(API_KEY), "the API key itself must never appear");

        // 3. 自检无需重启即成功 —— 证明网关用的是 DB 里的端点与密钥
        mockMvc.perform(post("/api/ai/self-test").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();

        // 4. GET /config：DB 行脱敏视图 + 生效快照
        MvcResult view = mockMvc.perform(get("/api/ai/config").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        String viewBody = view.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(viewBody.contains("\"apiKeyHint\":\"****-456\""), "hint shows last 4 chars");
        assertTrue(viewBody.contains("\"configSource\":\"在线配置\""));
        assertTrue(!viewBody.contains(API_KEY));
    }

    @Test
    void enableWithoutKeyIsRejectedWith400() throws Exception {
        startMockIfAbsent();
        String token = login("admin", "Admin@123");
        // 环境无密钥（test env ai.api-key 默认空）且本次未填写 → 护栏 400
        mockMvc.perform(put("/api/ai/config")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"model\":\"mock-model\"}"))
                .andExpect(status().isBadRequest());
        // 失败的保存不留半行脏数据
        mockMvc.perform(get("/api/ai/config").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(result -> assertTrue(!result.getResponse()
                        .getContentAsString(StandardCharsets.UTF_8).contains("\"enabled\":true,\"baseUrl\"")));
    }

    @Test
    void accountingSuggestionReturnsStructuredAdviceAndAudit() throws Exception {
        startMockIfAbsent();
        String token = login("admin", "Admin@123");
        saveEnabledConfig(token);
        long statementId = seedStatement("AICFG-" + suffix(), 1L, "泰拉贸易行");

        MvcResult result = mockMvc.perform(post("/api/ai/accounting-suggestion")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"statementId\":%d}".formatted(statementId)))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        var data = objectMapper.readTree(body).get("data");
        assertEquals("货款收入", data.get("businessCategory").asText());
        assertEquals("CUSTOMER", data.get("counterpartyType").asText());
        assertEquals(0.85, data.get("confidence").asDouble(), 1e-9);
        assertEquals(statementId, data.get("statementId").asLong());
        assertNotNull(data.get("durationMillis").asLong());
        assertTrue(!body.contains(API_KEY));

        // 审计：capability=accounting-suggestion 的 SUCCEEDED 行（哈希+摘要口径）
        AiCallLog audit = callLogMapper.selectList(new LambdaQueryWrapper<AiCallLog>()
                        .eq(AiCallLog::getCapability, AccountingSuggestionService.CAPABILITY)
                        .eq(AiCallLog::getStatus, "SUCCEEDED")
                        .orderByDesc(AiCallLog::getId))
                .get(0);
        assertEquals(64, audit.getPromptHash().length());
        assertTrue(audit.getPromptSummary().contains("泰拉贸易行"), "masked prompt summary retained");
        assertEquals(88, audit.getPromptTokens());
    }

    @Test
    void accountingSuggestionNonJsonModelOutputSurfaces502() throws Exception {
        startMockIfAbsent();
        String token = login("admin", "Admin@123");
        saveEnabledConfig(token);
        // 对手方名称带 BAD_JSON 标记 → mock 返回非 JSON → 502
        // （LLM 往返本身成功，审计记 SUCCEEDED；解析失败属于往返后的后处理）
        long statementId = seedStatement("AIBAD-" + suffix(), 1L, "BAD_JSON 不配合供应商");
        mockMvc.perform(post("/api/ai/accounting-suggestion")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"statementId\":%d}".formatted(statementId)))
                .andExpect(status().isBadGateway());
        AiCallLog audit = callLogMapper.selectList(new LambdaQueryWrapper<AiCallLog>()
                        .eq(AiCallLog::getCapability, AccountingSuggestionService.CAPABILITY)
                        .orderByDesc(AiCallLog::getId))
                .get(0);
        assertEquals("SUCCEEDED", audit.getStatus(), "the LLM round-trip itself is audited");
    }

    @Test
    void unknownStatementIsNotFound() throws Exception {
        startMockIfAbsent();
        String token = login("admin", "Admin@123");
        saveEnabledConfig(token);
        mockMvc.perform(post("/api/ai/accounting-suggestion")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"statementId\":999999999}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void suggestionEndpointRequiresAiUsePermission() throws Exception {
        startMockIfAbsent();
        String admin = login("admin", "Admin@123");
        saveEnabledConfig(admin);
        long statementId = seedStatement("AINOACC-" + suffix(), 1L, "无权限对手方");
        // VIEWER 角色无 ai:use → A1 端点 403（权限域继承而非旁路）
        String username = "noview_" + suffix();
        createUserWithRole(username, 4L);
        String viewer = login(username, PASSWORD);
        mockMvc.perform(post("/api/ai/accounting-suggestion")
                        .header("Authorization", bearer(viewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"statementId\":%d}".formatted(statementId)))
                .andExpect(status().isForbidden());
    }

    // ---- helpers ----

    private void saveEnabledConfig(String token) throws Exception {
        configMapper.deleteById(1L);
        mockMvc.perform(put("/api/ai/config")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"baseUrl":"http://127.0.0.1:%d","apiKey":"%s",\
                                "model":"mock-model","dailyLimitPerUser":50,\
                                "capabilities":{"self-test":true,"accounting-suggestion":true}}"""
                                .formatted(mockPort, API_KEY)))
                .andExpect(status().isOk());
    }

    /** 造一条公司域内的流水行（batch → record），返回流水 id。 */
    private long seedStatement(String statementNo, long companyId, String counterparty) {
        StatementImportBatch batch = new StatementImportBatch();
        batch.setCompanyId(companyId);
        batch.setBatchNo("BT-" + suffix());
        batch.setSourceType("FILE");
        batch.setStatus("COMPLETED");
        batch.setTotalCount(1);
        batch.setImportedCount(1);
        batch.setCreatedAt(LocalDateTime.now());
        batchMapper.insert(batch);

        StatementRecord record = new StatementRecord();
        record.setCompanyId(companyId);
        record.setBatchId(batch.getId());
        record.setStatementNo(statementNo);
        record.setTransactionTime(LocalDateTime.now().minusHours(2));
        record.setDirection("CREDIT");
        record.setAmount(new BigDecimal("12800.00"));
        record.setCurrency("CNY");
        record.setCounterpartyName(counterparty);
        record.setCounterpartyAccount("6222****0011");
        record.setSummary("货款");
        record.setRawPayload("{}");
        record.setValidationStatus("VALID");
        record.setReviewStatus("PENDING");
        record.setPushStatus("NOT_PUSHED");
        statementMapper.insert(record);
        return record.getId();
    }

    @Autowired
    private com.finance.system.domain.mapper.SysUserMapper userMapper;
    @Autowired
    private com.finance.system.domain.mapper.SysUserRoleMapper userRoleMapper;
    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private void createUserWithRole(String username, long roleId) {
        com.finance.system.domain.entity.SysUser user = new com.finance.system.domain.entity.SysUser();
        user.setCompanyId(1L);
        user.setUsername(username);
        user.setEmail(username + "@finflow.test");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        userRoleMapper.insert(new com.finance.system.domain.entity.SysUserRole(user.getId(), roleId));
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
