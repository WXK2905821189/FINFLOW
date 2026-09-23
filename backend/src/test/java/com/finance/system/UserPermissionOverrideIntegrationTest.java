package com.finance.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.mapper.SysUserMapper;
import com.finance.system.rbac.RbacService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V45（W17 包 D）：账号级权限覆盖集成测试。
 * 生效公式：有效权限 = (角色权限 ∪ 账号 GRANT) − 账号 DENY。
 * 共享 dev H2，所有断言按本类创建的用户名隔离；权限码取自权限目录，
 * 以创建后账号的 baseline 权限集为参照挑选 GRANT/DENY 目标，避免种子数据漂移。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class UserPermissionOverrideIntegrationTest {

    private static final String PASSWORD = "Passw0rd8";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private RbacService rbacService;

    @Test
    void grantAddsPermissionBeyondRoleAndDenyRemovesRolePermission() throws Exception {
        long nano = System.nanoTime();
        String username = "qa_ovr_" + nano;
        long userId = createUser(username);

        // 选一个 baseline 没有的权限码做 GRANT，一个 baseline 有的做 DENY
        List<String> catalog = permissionCatalog();
        List<String> baseline = rbacService.permissionCodesForUser(userId);
        String grantCode = catalog.stream().filter(code -> !baseline.contains(code)).findFirst().orElseThrow();
        String denyCode = baseline.stream().findFirst().orElseThrow();

        putOverrides(userId, "[{\"code\":\"" + grantCode + "\",\"effect\":\"GRANT\"}]");
        assertThat(rbacService.permissionCodesForUser(userId)).contains(grantCode);

        putOverrides(userId, "[{\"code\":\"" + denyCode + "\",\"effect\":\"DENY\"}]");
        assertThat(rbacService.permissionCodesForUser(userId)).doesNotContain(denyCode);

        // GET 列表与库中一致
        MvcResult list = mockMvc.perform(get("/api/users/" + userId + "/permission-overrides")
                        .header("Authorization", bearer(loginAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(userId))
                .andReturn();
        assertThat(list.getResponse().getContentAsString()).contains(denyCode).doesNotContain(grantCode);
    }

    @Test
    void grantAndDenySameCodeInOneRequestIsRejected() throws Exception {
        long nano = System.nanoTime();
        long userId = createUser("qa_conflict_" + nano);
        String code = permissionCatalog().get(0);

        mockMvc.perform(put("/api/users/" + userId + "/permission-overrides")
                        .header("Authorization", bearer(loginAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"code\":\"" + code + "\",\"effect\":\"GRANT\"},"
                                + "{\"code\":\"" + code + "\",\"effect\":\"DENY\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Conflicting GRANT and DENY")));
    }

    @Test
    void unknownPermissionCodeIsRejected() throws Exception {
        long nano = System.nanoTime();
        long userId = createUser("qa_unknown_" + nano);

        mockMvc.perform(put("/api/users/" + userId + "/permission-overrides")
                        .header("Authorization", bearer(loginAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"code\":\"totally:made:up\",\"effect\":\"GRANT\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Unknown permission code")));
    }

    @Test
    void denyRoleManageForAdminAccountOrSelfIsRejectedWith409() throws Exception {
        String adminToken = loginAdmin();
        long adminId = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, "admin")).getId();

        // 守卫①：对操作者本人 DENY role:manage → 409（操作者同时是超管，两条守卫都命中）
        mockMvc.perform(put("/api/users/" + adminId + "/permission-overrides")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"code\":\"role:manage\",\"effect\":\"DENY\"}]"))
                .andExpect(status().isConflict());

        // 守卫②：把新账号提升为第二个超管（ADMIN 角色后续可恢复，非「降级最后一个超管」），再对它
        // DENY role:manage —— 操作者（admin）≠ 目标，纯「超管账号」守卫 → 409
        long nano = System.nanoTime();
        String username = "qa_admin2_" + nano;
        long secondAdminId = createUser(username);
        mockMvc.perform(put("/api/users/" + secondAdminId).header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username + "@finflow.test\","
                                + "\"phone\":\"13800000000\",\"status\":\"ACTIVE\",\"roleIds\":[1],\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
        assertThat(rbacService.roleCodesForUser(secondAdminId)).contains("ADMIN");
        mockMvc.perform(put("/api/users/" + secondAdminId + "/permission-overrides")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"code\":\"role:manage\",\"effect\":\"DENY\"}]"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("administrator account")));
    }

    @Test
    void putReplacesPreviousOverridesAndWritesAudit() throws Exception {
        long nano = System.nanoTime();
        String username = "qa_replace_" + nano;
        long userId = createUser(username);

        List<String> catalog = permissionCatalog();
        List<String> baseline = rbacService.permissionCodesForUser(userId);
        String grantA = catalog.stream().filter(code -> !baseline.contains(code)).findFirst().orElseThrow();
        String grantB = catalog.stream().filter(code -> !baseline.contains(code) && !code.equals(grantA)).findFirst().orElseThrow();

        putOverrides(userId, "[{\"code\":\"" + grantA + "\",\"effect\":\"GRANT\"}]");
        assertThat(rbacService.permissionCodesForUser(userId)).contains(grantA);

        // 全量替换：旧覆盖清除，只剩新覆盖
        putOverrides(userId, "[{\"code\":\"" + grantB + "\",\"effect\":\"GRANT\"}]");
        assertThat(rbacService.permissionCodesForUser(userId)).contains(grantB).doesNotContain(grantA);

        MvcResult list = mockMvc.perform(get("/api/users/" + userId + "/permission-overrides")
                        .header("Authorization", bearer(loginAdmin())))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(list.getResponse().getContentAsString()).contains(grantB).doesNotContain(grantA);

        // 审计：USER_PERMISSION_OVERRIDE 事件落库
        MvcResult audit = mockMvc.perform(get("/api/audit/events")
                        .param("action", "USER_PERMISSION_OVERRIDE").param("size", "10")
                        .header("Authorization", bearer(loginAdmin())))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(audit.getResponse().getContentAsString()).contains(String.valueOf(userId));
    }

    private List<String> permissionCatalog() throws Exception {
        MvcResult permissions = mockMvc.perform(get("/api/rbac/permissions").header("Authorization", bearer(loginAdmin())))
                .andExpect(status().isOk())
                .andReturn();
        List<String> codes = new java.util.ArrayList<>();
        objectMapper.readTree(permissions.getResponse().getContentAsString()).get("data")
                .forEachRemaining(node -> codes.add(node.get("code").asText()));
        return codes;
    }

    private long createUser(String username) throws Exception {
        String token = loginAdmin();
        MvcResult created = mockMvc.perform(post("/api/users").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username + "@finflow.test\","
                                + "\"phone\":\"13800000000\",\"status\":\"ACTIVE\",\"roleIds\":[4],\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).get("data").get("id").asLong();
    }

    private void putOverrides(long userId, String bodyJson) throws Exception {
        mockMvc.perform(put("/api/users/" + userId + "/permission-overrides")
                        .header("Authorization", bearer(loginAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(status().isOk());
    }

    private String loginAdmin() throws Exception {
        return login("admin", "Admin@123");
    }

    private String login(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
