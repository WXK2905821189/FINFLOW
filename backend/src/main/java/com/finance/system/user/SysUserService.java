package com.finance.system.user;

import com.finance.system.audit.SystemAuditService;
import com.finance.system.domain.entity.AccountPreference;
import com.finance.system.domain.entity.AuthSession;
import com.finance.system.domain.entity.SysRole;
import com.finance.system.domain.mapper.AccountPreferenceMapper;
import com.finance.system.domain.mapper.AuthSessionMapper;
import com.finance.system.domain.mapper.SysUserRoleMapper;
import com.finance.system.rbac.RbacService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.finance.system.auth.dto.RegisterRequest;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.mapper.SysUserMapper;
import com.finance.system.user.dto.UserUpsertRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class SysUserService extends ServiceImpl<SysUserMapper, SysUser> {

    static final String PASSWORD_POLICY_MESSAGE =
            "Password must be at least 8 characters and contain both letters and digits";

    private final PasswordEncoder passwordEncoder;
    private final RbacService rbacService;
    private final SystemAuditService auditService;
    private final UserReferenceChecker referenceChecker;
    private final SysUserRoleMapper userRoleMapper;
    private final AuthSessionMapper authSessionMapper;
    private final AccountPreferenceMapper accountPreferenceMapper;

    public SysUserService(PasswordEncoder passwordEncoder, RbacService rbacService, SystemAuditService auditService,
                          UserReferenceChecker referenceChecker, SysUserRoleMapper userRoleMapper,
                          AuthSessionMapper authSessionMapper, AccountPreferenceMapper accountPreferenceMapper) {
        this.passwordEncoder = passwordEncoder;
        this.rbacService = rbacService;
        this.auditService = auditService;
        this.referenceChecker = referenceChecker;
        this.userRoleMapper = userRoleMapper;
        this.authSessionMapper = authSessionMapper;
        this.accountPreferenceMapper = accountPreferenceMapper;
    }

    public Optional<SysUser> findByUsername(String username) {
        return Optional.ofNullable(baseMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)));
    }

    public Optional<SysUser> findByLoginIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        String value = identifier.trim();
        Optional<SysUser> byUsername = findByUsername(value);
        return byUsername.isPresent()
                ? byUsername
                : findByEmail(value.toLowerCase());
    }

    public Optional<SysUser> findByEmail(String email) {
        return Optional.ofNullable(baseMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmail, email)));
    }

    @Transactional
    public SysUser register(RegisterRequest request) {
        ensureIdentityAvailable(request.username(), request.email(), null);
        validatePasswordPolicy(request.password(), true);
        SysUser user = new SysUser();
        user.setUsername(request.username().trim());
        user.setEmail(request.email().trim().toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStatus("PENDING");
        save(user);
        return user;
    }

    @Transactional
    public SysUser create(Long actorId, UserUpsertRequest request) {
        ensureIdentityAvailable(request.username(), request.email(), null);
        validatePasswordPolicy(request.password(), true);
        SysUser user = new SysUser();
        user.setCompanyId(1L);
        copyRequest(request, user);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        save(user);
        rbacService.replaceUserRoles(user.getId(), request.roleIds());
        auditService.record(actorId, "USER_CREATE", "USER", user.getUsername(), null, "SUCCESS",
                "status=" + user.getStatus() + ", roles=" + request.roleIds());
        return user;
    }

    @Transactional
    public SysUser updateUser(Long actorId, Long id, UserUpsertRequest request) {
        SysUser user = getById(id);
        if (user == null) {
            throw new BusinessException(404, "User not found");
        }
        ensureIdentityAvailable(request.username(), request.email(), id);
        String newStatus = request.status().trim().toUpperCase();
        boolean passwordChanged = request.password() != null && !request.password().isBlank();
        if (passwordChanged) {
            validatePasswordPolicy(request.password(), false);
        }
        ensureLastActiveAdminGuarded(id, user.getStatus(), newStatus, request.roleIds());
        String previousStatus = user.getStatus();
        copyRequest(request, user);
        if (passwordChanged) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            // GAP-7: bumping tokenVersion invalidates every outstanding session of this
            // user (JwtAuthenticationFilter checks auth_session against tokenVersion).
            user.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        }
        updateById(user);
        rbacService.replaceUserRoles(id, request.roleIds());
        auditService.record(actorId, "USER_UPDATE", "USER", user.getUsername(), null, "SUCCESS",
                "status=" + previousStatus + "->" + newStatus + ", roles=" + request.roleIds());
        if (passwordChanged) {
            auditService.record(actorId, "USER_PWD_RESET", "USER", user.getUsername(), null, "SUCCESS",
                    "sessions invalidated via tokenVersion");
        }
        if ("DISABLED".equals(newStatus) && !"DISABLED".equals(previousStatus)) {
            auditService.record(actorId, "USER_DISABLE", "USER", user.getUsername(), null, "SUCCESS", null);
        }
        return user;
    }

    /**
     * V36-W5（需求5）：物理删除账号。V34 拍板③「物理删除 + 外键检查」口径——
     * 任何业务/审计表仍引用该用户 → 409 提示改用「停用」；从属数据（角色绑定/登录会话/
     * 表格偏好）随删；禁止删自己与最后一个 ACTIVE ADMIN。删除后 auth_session 已清空，
     * 该用户所有登录立即失效（无需再动 tokenVersion）。
     */
    @Transactional
    public void delete(Long actorId, Long id) {
        if (actorId != null && actorId.equals(id)) {
            throw new BusinessException(409, "不能删除当前登录的账号");
        }
        SysUser user = getById(id);
        if (user == null) {
            throw new BusinessException(404, "User not found");
        }
        ensureLastActiveAdminNotDeleted(user, id);
        Map<String, Long> references = referenceChecker.countReferences(id);
        if (!references.isEmpty()) {
            String detail = references.entrySet().stream()
                    .map(entry -> entry.getKey() + " " + entry.getValue() + " 条")
                    .collect(Collectors.joining("、"));
            throw new BusinessException(409, "该账号存在业务或审计记录（" + detail + "），删除会破坏历史证据，请改用「停用」");
        }
        userRoleMapper.deleteByUserId(id);
        authSessionMapper.delete(new LambdaQueryWrapper<AuthSession>().eq(AuthSession::getUserId, id));
        accountPreferenceMapper.delete(new LambdaQueryWrapper<AccountPreference>().eq(AccountPreference::getUserId, id));
        removeById(id);
        auditService.record(actorId, "USER_DELETE", "USER", user.getUsername(), null, "SUCCESS",
                "physical delete; roles/sessions/preferences purged");
    }

    /** GAP-2 同源防锁死：删除最后一个 ACTIVE ADMIN 直接拒绝（先于引用检查，语义更明确）。 */
    private void ensureLastActiveAdminNotDeleted(SysUser user, Long userId) {
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            return;
        }
        SysRole adminRole = rbacService.findRoleByCode("ADMIN").orElse(null);
        if (adminRole == null) {
            return;
        }
        boolean isAdmin = rbacService.rolesForUser(userId).stream()
                .anyMatch(role -> role.getId().equals(adminRole.getId()));
        if (isAdmin && rbacService.countActiveAdminsExcluding(userId) == 0) {
            throw new BusinessException(409, "At least one active administrator must remain");
        }
    }

    /**
     * GAP-2 guard: an ACTIVE administrator may not be demoted or disabled when that change
     * would leave the system without any ACTIVE ADMIN account (anti-lockout).
     */
    private void ensureLastActiveAdminGuarded(Long userId, String currentStatus, String newStatus, List<Long> newRoleIds) {
        SysRole adminRole = rbacService.findRoleByCode("ADMIN").orElse(null);
        if (adminRole == null) {
            return;
        }
        boolean currentlyActiveAdmin = "ACTIVE".equalsIgnoreCase(currentStatus)
                && rbacService.rolesForUser(userId).stream().anyMatch(role -> role.getId().equals(adminRole.getId()));
        boolean willBeActiveAdmin = "ACTIVE".equals(newStatus) && newRoleIds.contains(adminRole.getId());
        if (currentlyActiveAdmin && !willBeActiveAdmin && rbacService.countActiveAdminsExcluding(userId) == 0) {
            throw new BusinessException(400, "At least one active administrator must remain");
        }
    }

    /** GAP-5: >=8 chars with at least one letter and one digit. */
    private void validatePasswordPolicy(String password, boolean required) {
        if (password == null || password.isBlank()) {
            if (required) {
                throw new BusinessException(400, "An initial password of at least 8 characters is required");
            }
            return;
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (password.length() < 8 || !hasLetter || !hasDigit) {
            throw new BusinessException(400, PASSWORD_POLICY_MESSAGE);
        }
    }

    private void copyRequest(UserUpsertRequest request, SysUser user) {
        user.setUsername(request.username().trim());
        user.setEmail(request.email().trim().toLowerCase());
        user.setPhone(request.phone());
        user.setStatus(request.status().trim().toUpperCase());
    }

    private void ensureIdentityAvailable(String username, String email, Long ignoredUserId) {
        findByUsername(username.trim()).filter(user -> !user.getId().equals(ignoredUserId))
                .ifPresent(user -> { throw new BusinessException(409, "Username already exists"); });
        findByEmail(email.trim().toLowerCase()).filter(user -> !user.getId().equals(ignoredUserId))
                .ifPresent(user -> { throw new BusinessException(409, "Email already exists"); });
    }
}
