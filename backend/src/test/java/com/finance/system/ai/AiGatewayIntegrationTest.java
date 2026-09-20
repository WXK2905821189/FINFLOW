package com.finance.system.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.domain.entity.AiCallLog;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.entity.SysUserRole;
import com.finance.system.domain.mapper.AiCallLogMapper;
import com.finance.system.domain.mapper.SysUserMapper;
import com.finance.system.domain.mapper.SysUserRoleMapper;
import com.sun.net.httpserver.HttpServer;
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
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 地基全链路（独立 Spring 上下文：ai.enabled=true + 本地 JDK HttpServer 冒充
 * OpenAI 兼容端点）。契约：
 * <ul>
 *   <li>自检走 网关 → 审计 全链路，成功/失败都落 ai_call_log（SUCCEEDED/FAILED）；</li>
 *   <li>密钥经环境位注入并随请求以 Bearer 头送达（mock 校验 401 护栏）；</li>
 *   <li>密钥与完整上下文不出现在 status 响应与审计行（哈希+摘要口径）。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AiGatewayIntegrationTest {

    private static final String PASSWORD = "Test@12345";
    private static final String API_KEY = "test-key-123";

    private static final AtomicBoolean FAIL_NEXT = new AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicInteger FAIL_REMAINING =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** W10：下一次调用返回 finish_reason=length 的截断响应（用于验证截断检测）。 */
    private static final AtomicBoolean TRUNCATE_NEXT = new AtomicBoolean(false);
    private static HttpServer mockLlm;
    private static int mockPort;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AiCallLogMapper callLogMapper;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private SysUserRoleMapper userRoleMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void aiProperties(DynamicPropertyRegistry registry) throws IOException {
        if (mockLlm == null) {
            mockLlm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            mockLlm.createContext("/chat/completions", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (!("Bearer " + API_KEY).equals(auth)) {
                    respond(exchange, 401, "{\"error\":{\"message\":\"bad key\"}}");
                    return;
                }
                String request = new String(body, StandardCharsets.UTF_8);
                // Failure quota (not a one-shot flag): the gateway retries 5xx once, so a
                // simulated outage must fail across the whole retry window to surface as 502.
                boolean failing = request.contains("FAIL_ME")
                        || FAIL_NEXT.getAndSet(false)
                        || FAIL_REMAINING.getAndUpdate(x -> Math.max(0, x - 1)) > 0;
                if (failing) {
                    respond(exchange, 500, "{\"error\":{\"message\":\"provider exploded\"}}");
                    return;
                }
                // W10：截断响应——HTTP 200 但 finish_reason=length、content 是半截 JSON。
                // gateway 必须把它识别成失败（否则业务层会退化成模糊的「AI 建议不可用」）。
                if (TRUNCATE_NEXT.getAndSet(false)) {
                    respond(exchange, 200, """
                            {"id":"mock-trunc","model":"mock-model","choices":[{"index":0,\
                            "message":{"role":"assistant","content":"{\\"businessCategory\\":\\"往来款\\""},\
                            "finish_reason":"length"}],\
                            "usage":{"prompt_tokens":21,"completion_tokens":512,"total_tokens":533}}""");
                    return;
                }
                respond(exchange, 200, """
                        {"id":"mock-1","model":"mock-model","choices":[{"index":0,"message":\
                        {"role":"assistant","content":"PONG"},"finish_reason":"stop"}],\
                        "usage":{"prompt_tokens":21,"completion_tokens":2,"total_tokens":23}}""");
            });
            mockLlm.start();
            mockPort = mockLlm.getAddress().getPort();
        }
        registry.add("ai.enabled", () -> "true");
        registry.add("ai.base-url", () -> "http://127.0.0.1:" + mockPort);
        registry.add("ai.api-key", API_KEY::toString);
        registry.add("ai.model", () -> "mock-model");
        registry.add("ai.capabilities.self-test", () -> "true");
    }

    @AfterAll
    static void stopMock() {
        if (mockLlm != null) {
            mockLlm.stop(0);
        }
    }

    @Test
    void selfTestRoundTripIsAuditedAndNeverLeaksSecrets() throws Exception {
        String token = login("admin", "Admin@123");

        // 1. status: gateway on, key flagged as configured, but the secret itself never leaves
        MvcResult statusResult = mockMvc.perform(get("/api/ai/status")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        String statusBody = statusResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(statusBody.contains("\"enabled\":true"), "gateway reports enabled");
        assertTrue(statusBody.contains("\"apiKeyConfigured\":true"), "key presence is exposed");
        assertTrue(!statusBody.contains(API_KEY), "the API key itself must never appear in responses");

        // 2. self-test round-trip succeeds and is audited as SUCCEEDED
        MvcResult selfTest = mockMvc.perform(post("/api/ai/self-test")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        String body = selfTest.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals("PONG", objectMapper.readTree(body).get("data").get("reply").asText());
        assertTrue(body.contains("\"promptTokens\":21"), "usage tokens are surfaced for cost control");

        // 3. audit row: success with prompt hash + truncated summary, no full context
        AiCallLog successRow = callLogMapper.selectList(new LambdaQueryWrapper<AiCallLog>()
                        .eq(AiCallLog::getCapability, AiGatewayService.SELF_TEST)
                        .eq(AiCallLog::getStatus, "SUCCEEDED")
                        .orderByDesc(AiCallLog::getId))
                .get(0);
        assertEquals(64, successRow.getPromptHash().length(), "prompt is fingerprinted (SHA-256)");
        assertTrue(successRow.getPromptSummary().contains("ping"), "masked summary retained");
        assertEquals(21, successRow.getPromptTokens());
        assertEquals(23, successRow.getTotalTokens());

        // 4. failing round-trip: the outage spans the gateway retry window (maxRetries=1
        //    → 2 attempts), the endpoint surfaces 502, audit row FAILED with error message
        FAIL_REMAINING.set(2);
        mockMvc.perform(post("/api/ai/self-test")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadGateway());
        AiCallLog failedRow = callLogMapper.selectList(new LambdaQueryWrapper<AiCallLog>()
                        .eq(AiCallLog::getCapability, AiGatewayService.SELF_TEST)
                        .eq(AiCallLog::getStatus, "FAILED")
                        .orderByDesc(AiCallLog::getId))
                .get(0);
        assertTrue(failedRow.getErrorMessage().contains("500"), "failure diagnosis carries the provider status");
    }

    @Test
    void truncatedOutputIsReportedInsteadOfSilentlySucceeding() throws Exception {
        String token = login("admin", "Admin@123");
        // HTTP 200 + finish_reason=length + 半截 JSON：必须显式失败，不能当成功放行
        TRUNCATE_NEXT.set(true);
        MvcResult res = mockMvc.perform(post("/api/ai/self-test")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadGateway())
                .andReturn();
        String body = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("截断"), "truncation is named explicitly: " + body);
        assertTrue(body.contains("max_tokens"), "diagnosis points at max_tokens: " + body);

        // 审计行同样带截断原因，便于事后排查
        AiCallLog row = callLogMapper.selectList(new LambdaQueryWrapper<AiCallLog>()
                        .eq(AiCallLog::getCapability, AiGatewayService.SELF_TEST)
                        .eq(AiCallLog::getStatus, "FAILED")
                        .orderByDesc(AiCallLog::getId))
                .get(0);
        assertTrue(row.getErrorMessage().contains("截断"), "audit row carries the truncation reason");
    }

    @Test
    void viewerRoleHasNoAiAccess() throws Exception {
        String username = "noai_" + suffix();
        createUserWithRole(username, 4L);
        String viewer = login(username, PASSWORD);
        mockMvc.perform(get("/api/ai/status")
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/ai/self-test")
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isForbidden());
    }

    // ---- helpers ----

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void createUserWithRole(String username, long roleId) {
        SysUser user = new SysUser();
        user.setCompanyId(1L);
        user.setUsername(username);
        user.setEmail(username + "@finflow.test");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        userRoleMapper.insert(new SysUserRole(user.getId(), roleId));
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
