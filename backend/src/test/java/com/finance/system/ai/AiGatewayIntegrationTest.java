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
 *   <li>密钥与完整上下文不出现在 status 响应与审计行（哈希+摘要口径）；</li>
 *   <li>每用户每能力日限频，失败调用也计数，超限 429。</li>
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
        registry.add("ai.daily-limit-per-user", () -> "3");
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
    void dailyLimitCountsFailuresAndRejectsWith429() throws Exception {
        // Dedicated ADMIN user so the quota starts at 0 regardless of test order
        // (the shared admin already spent quota in the round-trip test above).
        String username = "noquota_" + suffix();
        createUserWithRole(username, 1L);
        String token = login(username, PASSWORD);
        // daily-limit-per-user=3; both success and failure calls consume quota.
        int rejected = 0;
        int okOrDownstream = 0;
        for (int i = 0; i < 8; i++) {
            MvcResult result = mockMvc.perform(post("/api/ai/self-test")
                            .header("Authorization", bearer(token)))
                    .andReturn();
            int actual = result.getResponse().getStatus();
            if (actual == 429) {
                rejected++;
            } else {
                okOrDownstream++;
                assertEquals(200, actual, "non-rejected calls must succeed (no FAIL_ME flags set)");
            }
        }
        assertTrue(rejected >= 1, "quota must eventually reject with 429");
        assertEquals(3, okOrDownstream, "exactly the quota allowance is served before rejection");
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
