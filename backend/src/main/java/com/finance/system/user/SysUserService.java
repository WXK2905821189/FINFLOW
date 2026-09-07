package com.finance.system.user;

import com.finance.system.audit.SystemAuditService;
import com.finance.system.rbac.RbacService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.finance.system.auth.dto.RegisterRequest;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.SysRole;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.mapper.SysUserMapper;
import com.finance.system.user.dto.UserUpsertRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class SysUserService extends ServiceImpl<SysUserMapper, SysUser> {

    static final String PASSWORD_POLICY_MESSAGE =
            "Password must be at least 8 characters and contain both letters and digits";

    private final PasswordEncoder passwordEncoder;
    private final RbacService rbacService;
    private final SystemAuditService auditService;

    public SysUserService(PasswordEncoder passwordEncoder, RbacService rbacService, SystemAuditService auditService) {
        this.passwordEncoder = passwordEncoder;
        this.rbacService = rbacService;
        this.auditService = auditService;
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
