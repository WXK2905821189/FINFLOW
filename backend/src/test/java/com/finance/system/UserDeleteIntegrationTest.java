package com.finance.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.AccountPreference;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.mapper.AccountPreferenceMapper;
import com.finance.system.domain.mapper.AuthSessionMapper;
import com.finance.system.domain.mapper.SysUserMapper;
import com.finance.system.rbac.RbacService;
import com.finance.system.user.SysUserService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V36-W5（需求5）验收：DELETE /api/users/{id} 物理删除 + 引用检查（409 → 请改用停用）、
 * 禁删自己、禁删最后一个 ACTIVE ADMIN、从属数据（角色绑定/会话/偏好）随删、
 * 删除后旧会话立即失效、中文用户名可注册可登录（V34 拍板③代码层验证）。
 * 共享 dev H2，所有造数用 nano 后缀隔离；last-admin 断言用 Assumptions 防脏库误报。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class UserDeleteIntegrationTest {

    private static final String PASSWORD = "Passw0rd8";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private SysUserService userService;
    @Autowired
    private RbacService rbacService;
    @Autowired
    private AuthSessionMapper authSessionMapper;
    @Autowired
    private AccountPreferenceMapper accountPreferenceMapper;

    @Test
    void deleteUnreferencedUserPurgesDependentsAndKillsSessions() throws Exception {
        String adminToken = loginAdmin();
        long nano = System.nanoTime();
        String username = "qa_del_" + nano;

        long userId = createUserId(adminToken, username, PASSWORD, "[4]");

        // 不真登录（登录会写 LOGIN_SUCCESS 审计 → 该用户变成有引用 → 409）。
        // 直接造从属数据：auth_session 会话行 + 表格偏好，两者都应随账号删除。
        com.finance.system.domain.entity.AuthSession session = new com.finance.system.domain.entity.AuthSession();
        session.setUserId(userId);
        session.setTokenId("qa-del-" + nano);
        session.setTokenVersion(0);
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        session.setCreatedAt(LocalDateTime.now());
        authSessionMapper.insert(session);
        AccountPreference preference = new AccountPreference();
        preference.setUserId(userId);
        preference.setScopeKey("bankdata.balances");
        preference.setPayload("{}");
        preference.setCreatedAt(LocalDateTime.now());
        preference.setUpdatedAt(LocalDateTime.now());
        accountPreferenceMapper.insert(preference);
        assertThat(sessionCountOf(userId)).isEqualTo(1);

        mockMvc.perform(delete("/api/users/" + userId).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User deleted"));

        assertThat(userMapper.selectById(userId)).isNull();
        assertThat(sessionCountOf(userId)).isZero();
        assertThat(preferenceCountOf(userId)).isZero();
        assertThat(rbacService.rolesForUser(userId)).isEmpty();

        // 删除后账号本身无法再登录（用户行已消失）
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteUserWithAuditReferenceIsRejectedWithDisableHint() throws Exception {
        String adminToken = loginAdmin();
        long nano = System.nanoTime();
        String username = "qa_del_ref_" + nano;

        long userId = createUserId(adminToken, username, PASSWORD, "[4]");
        // 登录成功会写 system_audit（LOGIN_SUCCESS，actor_id=该用户）→ 构成审计引用
        login(username, PASSWORD);

        mockMvc.perform(delete("/api/users/" + userId).header("Authorization", bearer(adminToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("请改用「停用」")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("系统审计")));

        // 用户仍在，可继续登录（引用检查不产生副作用）
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void cannotDeleteSelf() throws Exception {
        String adminToken = loginAdmin();
        SysUser admin = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, "admin"));
        assertThat(admin).isNotNull();

        mockMvc.perform(delete("/api/users/" + admin.getId()).header("Authorization", bearer(adminToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("不能删除当前登录的账号"));
    }

    @Test
    void lastActiveAdminCannotBeDeleted() throws Exception {
        SysUser admin = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, "admin"));
        assertThat(admin).isNotNull();
        // 仅当 admin 是当前唯一 ACTIVE ADMIN 时本用例语义成立（共享库防污染）
        Assumptions.assumeTrue(rbacService.countActiveAdminsExcluding(admin.getId()) == 0);

        // service 层直调不走 @PreAuthorize，操作者传一个不存在的 id 即可绕开 self 检查；
        // HTTP 层该场景当前不可达（user:manage 仅 ADMIN 持有，唯一 admin 自删先撞 self），
        // 本用例验证的是 service 层防锁死纵深防御（未来 user:manage 放开给其他角色时兜底）。
        long ghostActorId = 9_000_000_000L + System.nanoTime() % 1_000_000L;
        assertThatThrownBy(() -> userService.delete(ghostActorId, admin.getId()))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getMessage()).contains("active administrator");
                });
        assertThat(userMapper.selectById(admin.getId())).isNotNull();
    }

    @Test
    void chineseUsernameCanBeCreatedAndLogsIn() throws Exception {
        String adminToken = loginAdmin();
        long nano = System.nanoTime();
        String username = "qa_财务_" + nano;

        MvcResult created = mockMvc.perform(post("/api/users").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(username, PASSWORD, "ACTIVE", "[4]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username))
                .andReturn();
        long userId = objectMapper.readTree(created.getResponse().getContentAsString()).get("data").get("id").asLong();

        String token = login(username, PASSWORD);
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username));

        // 清理：该用户只留下自己这次登录的审计，服务层直调同样会被引用检查拒绝——
        // 停用即可（中文用户名验收只关心可注册可登录）
        mockMvc.perform(delete("/api/users/" + userId).header("Authorization", bearer(adminToken)))
                .andExpect(status().isConflict());
    }

    private long createUserId(String adminToken, String username, String password, String roleIdsJson) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/users").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(username, password, "ACTIVE", roleIdsJson)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).get("data").get("id").asLong();
    }

    private long sessionCountOf(Long userId) {
        Long count = authSessionMapper.selectCount(
                new LambdaQueryWrapper<com.finance.system.domain.entity.AuthSession>()
                        .eq(com.finance.system.domain.entity.AuthSession::getUserId, userId));
        return count == null ? 0 : count;
    }

    private long preferenceCountOf(Long userId) {
        Long count = accountPreferenceMapper.selectCount(
                new LambdaQueryWrapper<AccountPreference>().eq(AccountPreference::getUserId, userId));
        return count == null ? 0 : count;
    }

    private String upsertBody(String username, String password, String status, String roleIdsJson) {
        return "{\"username\":\"" + username + "\",\"email\":\"" + username + "@finflow.test\","
                + "\"phone\":\"13800000000\",\"status\":\"" + status + "\",\"roleIds\":" + roleIdsJson
                + ",\"password\":\"" + password + "\"}";
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
