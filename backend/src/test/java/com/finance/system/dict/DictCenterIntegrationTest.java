package com.finance.system.dict;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.entity.SysUserRole;
import com.finance.system.domain.mapper.SysDictItemMapper;
import com.finance.system.domain.mapper.SysUserMapper;
import com.finance.system.domain.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 字典中心（V26）契约锁定：类型/项全生命周期走 API（共享 H2 会被其它测试类改动，
 * 一律用唯一后缀建码，不依赖种子行）；扩展属性必须是 JSON object（数组/非法 JSON 拒绝）；
 * 消费方端点只回 ACTIVE 项；类型删除在还有项时拒绝、force 才连带；
 * system:dict:manage 只授予角色 1/2，角色 3 一律 403。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DictCenterIntegrationTest {

    private static final String PASSWORD = "Test@12345";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private SysUserRoleMapper userRoleMapper;
    @Autowired
    private SysDictItemMapper dictItemMapper;

    @Test
    void dictLifecycleExtraJsonGuardAndConsumerRead() throws Exception {
        String token = login("admin", "Admin@123");
        String typeCode = "dict_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // 1. create type → consumer read returns empty before any item
        long typeId = createType(token, typeCode);
        assertTrue(consumerItems(token, typeCode).isEmpty(), "new type exposes no consumer items");

        // 2. duplicate type code is rejected
        mockMvc.perform(post("/api/system/dicts/types")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeCode\":\"" + typeCode + "\",\"name\":\"重复\"}"))
                .andExpect(status().isBadRequest());

        // 3. invalid type code formats are rejected
        mockMvc.perform(post("/api/system/dicts/types")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeCode\":\"Bad-Code\",\"name\":\"非法\"}"))
                .andExpect(status().isBadRequest());

        // 4. item with object extraJson is normalized and readable by consumers
        long itemId = createItem(token, typeId, "主体甲", "{\"税号\":\"91TEST\",\"开户行\":\"招商银行\"}");
        JsonNode consumerItems = consumerItems(token, typeCode);
        assertEquals(1, consumerItems.size());
        assertTrue(consumerItems.get(0).get("extraJson").asText().contains("91TEST"),
                "extraJson is stored verbatim for consumers");

        // 5. array / broken JSON extra payloads are refused
        mockMvc.perform(post("/api/system/dicts/types/" + typeId + "/items")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemCode\":\"bad1\",\"label\":\"数组\",\"extraJson\":\"[1,2]\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/system/dicts/types/" + typeId + "/items")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemCode\":\"bad2\",\"label\":\"坏JSON\",\"extraJson\":\"{税号}\"}"))
                .andExpect(status().isBadRequest());

        // 6. disabled item drops out of the consumer view but stays in management view
        long disabledId = createItem(token, typeId, "停用主体", null, "DISABLED");
        assertEquals(1, consumerItems(token, typeCode).size(), "DISABLED items are not exposed");
        JsonNode managementItems = managementItems(token, typeId);
        assertEquals(2, managementItems.size());

        // 7. deleting the type while items exist is refused without force
        mockMvc.perform(delete("/api/system/dicts/types/" + typeId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());

        // 8. force delete cascades and the consumer view empties
        mockMvc.perform(delete("/api/system/dicts/types/" + typeId + "?force=true")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        assertTrue(consumerItems(token, typeCode).isEmpty());
        mockMvc.perform(get("/api/system/dicts/types/" + typeId + "/items")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        // items were cascaded away (type endpoints 404 above; row-level proof via mapper)
        assertEquals(0, dictItemMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.finance.system.domain.entity.SysDictItem>()
                        .eq(com.finance.system.domain.entity.SysDictItem::getTypeId, typeId)));
    }

    @Test
    void dictManagementIsForbiddenWithoutThePermission() throws Exception {
        String unprivileged = createUserWithoutDictPermission();
        mockMvc.perform(get("/api/system/dicts/types")
                        .header("Authorization", bearer(unprivileged)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/system/dicts/types")
                        .header("Authorization", bearer(unprivileged))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeCode\":\"nope_x\",\"name\":\"越权\"}"))
                .andExpect(status().isForbidden());
    }

    // ---- helpers ----

    private long createType(String token, String typeCode) throws Exception {
        String body = mockMvc.perform(post("/api/system/dicts/types")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeCode\":\"" + typeCode + "\",\"name\":\"字典测试类型\",\"description\":\"集成测试\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data").get("id").asLong();
    }

    private long createItem(String token, long typeId, String label, String extraJson) throws Exception {
        return createItem(token, typeId, label, extraJson, "ACTIVE");
    }

    private long createItem(String token, long typeId, String label, String extraJson, String status) throws Exception {
        String extra = extraJson == null ? "" : ",\"extraJson\":\"" + extraJson.replace("\"", "\\\"") + "\"";
        String body = mockMvc.perform(post("/api/system/dicts/types/" + typeId + "/items")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemCode\":\"it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6)
                                + "\",\"label\":\"" + label + "\",\"status\":\"" + status + "\"" + extra + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data").get("id").asLong();
    }

    private JsonNode consumerItems(String token, String typeCode) throws Exception {
        String body = mockMvc.perform(get("/api/system/dicts/" + typeCode + "/items")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data");
    }

    private JsonNode managementItems(String token, long typeId) throws Exception {
        String body = mockMvc.perform(get("/api/system/dicts/types/" + typeId + "/items")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data");
    }

    /** Role 3 never received system:dict:manage (V26 grants it to roles 1 and 2 only). */
    private String createUserWithoutDictPermission() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SysUser user = new SysUser();
        user.setCompanyId(1L);
        user.setUsername("nodict_" + suffix);
        user.setEmail("nodict_" + suffix + "@finflow.test");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        userRoleMapper.insert(new SysUserRole(user.getId(), 3L));
        return login(user.getUsername(), PASSWORD);
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
