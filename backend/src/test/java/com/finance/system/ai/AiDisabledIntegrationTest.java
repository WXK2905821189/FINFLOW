package com.finance.system.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 地基默认态（fail-closed，走共享 H2 上下文）：未配置 ai.* 时——
 * 状态端点如实回报 disabled；自检端点对持权用户也 403（能力未开）；
 * 能力开关是逐能力的，网关总关时无一例外放行。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AiDisabledIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void aiEndpointsStayClosedWhenDisabled() throws Exception {
        String token = login("admin", "Admin@123");

        String body = mockMvc.perform(get("/api/ai/status")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"enabled\":false"), "gateway reports disabled by default");
        assertTrue(body.contains("\"apiKeyConfigured\":false"), "no key configured by default");

        // admin holds ai:config, yet self-test is refused: the capability switch is off
        mockMvc.perform(post("/api/ai/self-test")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/ai/call-logs")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
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
