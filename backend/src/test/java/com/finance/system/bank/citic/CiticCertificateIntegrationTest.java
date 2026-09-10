package com.finance.system.bank.citic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The CITIC cloud-certificate download is a single-use, bank-coordinated operation, so the
 * endpoint contract is locked here without ever touching the real SDK: unauthenticated calls
 * are rejected (401), blank parameters answer 400 before anything else, and with parameters
 * supplied in the shared H2 context (REAL transport disabled) the admin call must surface the
 * unavailable-boundary 501 instead of a misleading 500/404. The happy path against TSEA is
 * exercised during joint testing, not in CI.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "bankdata.adapter.citic.sdk.cert-path=build/tmp/cert-it")
class CiticCertificateIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void downloadRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/citic-certificate/download")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void downloadRejectsBlankParameters() throws Exception {
        String token = login();
        mockMvc.perform(post("/api/citic-certificate/download")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadSurfacesUnavailableBoundaryWhenRealTransportDisabled() throws Exception {
        String token = login();
        mockMvc.perform(post("/api/citic-certificate/download")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"downloadCode\":\"it-code\",\"orgCode\":\"it-org\"}"))
                .andExpect(status().isNotImplemented());
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data").get("accessToken").asText();
    }
}
