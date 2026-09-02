package com.finance.system;

import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the validation (rules / accounting mappings) and user management endpoints,
 * which previously had no direct test coverage (JaCoCo baseline 2026-09-01:
 * validation 45.1%, user 13.1% instruction coverage).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ValidationAndUserEndpointsIntegrationTest {

    private static final String PASSWORD = "Test@12345";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SysUserMapper userMapper;

    @Test
    void validationRuleLifecycleDraftVersioningAndActivation() throws Exception {
        String token = login("admin", "Admin@123");
        String ruleCode = "QA-RULE-" + System.nanoTime();

        MvcResult created = mockMvc.perform(post("/api/validation/rules")
                        .header("Authorization", bearer(token))
                        .header("X-Request-Id", "qa-rule-req-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleCode\":\"" + ruleCode + "\",\"name\":\"QA 金额区间规则\",\"ruleType\":\"amount_range\","
                                + "\"expression\":\"amount between 100 and 5000\",\"priority\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.ruleType").value("AMOUNT_RANGE"))
                .andExpect(jsonPath("$.data.priority").value(10))
                .andReturn();
        long ruleId = objectMapper.readTree(created.getResponse().getContentAsString()).get("data").get("id").asLong();

        mockMvc.perform(post("/api/validation/rules")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleCode\":\"" + ruleCode + "\",\"name\":\"QA 金额区间规则 v2\",\"ruleType\":\"AMOUNT_RANGE\","
                                + "\"expression\":\"amount between 100 and 9000\",\"priority\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(2));

        mockMvc.perform(post("/api/validation/rules/" + ruleId + "/activate")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mockMvc.perform(get("/api/validation/rules").param("status", "ACTIVE")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[*].ruleCode")
                        .value(org.hamcrest.Matchers.hasItem(ruleCode)));

        mockMvc.perform(get("/api/validation/rules")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[*].status")
                        .value(org.hamcrest.Matchers.hasItem("DRAFT")));
    }

    @Test
    void accountingMappingRejectsBadDirectionNormalizesAndActivates() throws Exception {
        String token = login("admin", "Admin@123");
        String mappingCode = "QA-MAP-" + System.nanoTime();

        mockMvc.perform(post("/api/validation/mappings")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mappingCode\":\"" + mappingCode + "\",\"name\":\"QA 手续费映射\",\"direction\":\"SIDeways\","
                                + "\"counterpartyKeyword\":\"银行手续费\",\"debitSubject\":\"6602\",\"creditSubject\":\"1002\","
                                + "\"voucherTemplate\":\"BANK_FEE\"}"))
                .andExpect(status().isBadRequest());

        MvcResult created = mockMvc.perform(post("/api/validation/mappings")
                        .header("Authorization", bearer(token))
                        .header("X-Request-Id", "qa-map-req-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mappingCode\":\"" + mappingCode + "\",\"name\":\"QA 手续费映射\",\"direction\":\"expense\","
                                + "\"counterpartyKeyword\":\"银行手续费\",\"debitSubject\":\"6602\",\"creditSubject\":\"1002\","
                                + "\"voucherTemplate\":\"BANK_FEE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.direction").value("EXPENSE"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andReturn();
        long mappingId = objectMapper.readTree(created.getResponse().getContentAsString()).get("data").get("id").asLong();

        mockMvc.perform(post("/api/validation/mappings/" + mappingId + "/activate")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mockMvc.perform(get("/api/validation/mappings").param("status", "ACTIVE")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[*].mappingCode")
                        .value(org.hamcrest.Matchers.hasItem(mappingCode)));
    }

    @Test
    void userCreateUpdateAndDuplicateIdentityConflict() throws Exception {
        String token = login("admin", "Admin@123");
        String username = "qa_user_" + System.nanoTime();
        String email = username + "@finflow.test";

        MvcResult createdResult = mockMvc.perform(post("/api/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"phone\":\"13800000000\","
                                + "\"status\":\"active\",\"roleIds\":[2],\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.email").value(email))
                .andReturn();
        long userId = objectMapper.readTree(createdResult.getResponse().getContentAsString()).get("data").get("id").asLong();

        SysUser persisted = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getId, userId));
        assertThat(persisted).isNotNull();
        assertThat(persisted.getStatus()).isEqualTo("ACTIVE");

        mockMvc.perform(post("/api/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"other_" + email + "\",\"status\":\"ACTIVE\","
                                + "\"roleIds\":[2],\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/api/users/" + userId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"phone\":\"13900000000\","
                                + "\"status\":\"SUSPENDED\",\"roleIds\":[2,3],\"password\":\"" + PASSWORD + "x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.phone").value("13900000000"));

        mockMvc.perform(post("/api/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"qa_short_" + System.nanoTime() + "\",\"email\":\"qa_short_"
                                + System.nanoTime() + "@finflow.test\",\"status\":\"ACTIVE\",\"roleIds\":[2],\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
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
