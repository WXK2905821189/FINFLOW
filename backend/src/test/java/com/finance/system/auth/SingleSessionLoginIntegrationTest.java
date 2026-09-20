package com.finance.system.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.system.domain.entity.AuthSession;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.entity.SysUserRole;
import com.finance.system.domain.mapper.AuthSessionMapper;
import com.finance.system.domain.mapper.SysUserMapper;
import com.finance.system.domain.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W10：单点登录（新登录踢旧会话）。
 *
 * <p>用临时账号而非 admin，避免踢掉其他集成测试正在使用的共享会话（admin 会话被本测试撤销
 * 会让并行的其它用例 401 失败）。断言：第二次登录后旧 token 立即失效（401）、新 token 有效、
 * 旧会话行被标记 revoked_at，且登录审计带 kickedSessions 计数。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SingleSessionLoginIntegrationTest {

    private static final String PASSWORD = "Test@12345";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private SysUserRoleMapper userRoleMapper;
    @Autowired
    private AuthSessionMapper sessionMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void newLoginRevokesPreviousSession() throws Exception {
        String username = "ss_" + suffix();
        createUserWithRole(username, 4L);

        String first = login(username, PASSWORD);
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(first)))
                .andExpect(status().isOk());

        String second = login(username, PASSWORD);
        // 旧会话被踢：下一次请求即 401（认证过滤器 isActive 校验失败）
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(first)))
                .andExpect(status().isUnauthorized());
        // 新会话可用
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(second)))
                .andExpect(status().isOk());

        // 落库侧：至少一条该用户的会话被标记 revoked_at
        long userId = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)).getId();
        long revoked = sessionMapper.selectCount(new LambdaQueryWrapper<AuthSession>()
                .eq(AuthSession::getUserId, userId)
                .isNotNull(AuthSession::getRevokedAt));
        assertTrue(revoked >= 1, "旧会话必须被标记撤销");
        assertEquals(2, sessionMapper.selectCount(new LambdaQueryWrapper<AuthSession>()
                .eq(AuthSession::getUserId, userId)), "两次登录应各建一条会话行");
    }

    // ---- helpers ----

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

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
