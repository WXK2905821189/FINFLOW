package com.finance.system.rbac;

import com.finance.system.audit.SystemAuditService;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.entity.SysUserPermission;
import com.finance.system.domain.mapper.SysUserMapper;
import com.finance.system.domain.mapper.SysUserPermissionMapper;
import com.finance.system.rbac.dto.UserPermissionOverrideItem;
import com.finance.system.rbac.dto.UserPermissionOverrideItemRequest;
import com.finance.system.rbac.dto.UserPermissionOverrideResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * V45（W17 包 D）：账号级权限覆盖管理。
 *
 * 规则（测试锁定）：
 *  - code 必须存在于 sys_permission 权限目录，否则 400；
 *  - 同一请求内同一 code 不得同时出现 GRANT 与 DENY，否则 400（实现定死：拒绝）；
 *  - 防自锁：不允许对 ADMIN（超管）账号或操作人本人 DENY role:manage，否则 409；
 *  - PUT 全量替换：先删后插，逐行断言影响行数 == 1。
 */
@Service
public class UserPermissionOverrideService {

    /** 防自锁权限点：剔除它会让账号失去权限管理能力（配合 role:manage 守卫）。 */
    private static final String ROLE_MANAGE = "role:manage";

    private final SysUserPermissionMapper overrideMapper;
    private final RbacService rbacService;
    private final SysUserMapper userMapper;
    private final SystemAuditService auditService;

    public UserPermissionOverrideService(SysUserPermissionMapper overrideMapper,
                                         RbacService rbacService,
                                         SysUserMapper userMapper,
                                         SystemAuditService auditService) {
        this.overrideMapper = overrideMapper;
        this.rbacService = rbacService;
        this.userMapper = userMapper;
        this.auditService = auditService;
    }

    public List<UserPermissionOverrideItem> listForUser(Long userId) {
        requireUser(userId);
        return overrideMapper.findByUserId(userId).stream()
                .map(override -> new UserPermissionOverrideItem(override.getPermissionCode(), override.getEffect()))
                .toList();
    }

    @Transactional
    public List<UserPermissionOverrideItem> replaceAll(Long actorId, Long userId, List<UserPermissionOverrideItemRequest> items) {
        requireUser(userId);
        List<UserPermissionOverrideItemRequest> requests = items == null ? List.of() : items;
        validate(userId, actorId, requests);

        List<String> before = listForUser(userId).stream()
                .map(item -> item.effect() + ":" + item.code()).sorted().toList();

        int deleted = overrideMapper.deleteByUserId(userId);
        List<SysUserPermission> toInsert = requests.stream()
                .map(item -> new SysUserPermission(userId, item.code(), item.effect(), actorId))
                .toList();
        for (SysUserPermission override : toInsert) {
            int inserted = overrideMapper.insert(override);
            if (inserted != 1) {
                throw new BusinessException(500, "Failed to save permission override for " + override.getPermissionCode());
            }
        }

        List<String> after = toInsert.stream()
                .map(override -> override.getEffect() + ":" + override.getPermissionCode()).sorted().toList();
        auditService.record(actorId, "USER_PERMISSION_OVERRIDE", "USER", String.valueOf(userId), null, "SUCCESS",
                "overrides " + before + " -> " + after + " (deleted=" + deleted + ")");
        return listForUser(userId);
    }

    private void validate(Long userId, Long actorId, List<UserPermissionOverrideItemRequest> requests) {
        // 权限目录校验：code 必须真实存在（瞎编 400）
        Set<String> catalog = rbacService.listPermissions().stream()
                .map(com.finance.system.domain.entity.SysPermission::getCode).collect(Collectors.toSet());
        Set<String> grantCodes = new HashSet<>();
        Set<String> denyCodes = new HashSet<>();
        for (UserPermissionOverrideItemRequest item : requests) {
            if (item.code() == null || item.code().isBlank() || !catalog.contains(item.code())) {
                throw new BusinessException(400, "Unknown permission code: " + item.code());
            }
            if (SysUserPermission.EFFECT_GRANT.equals(item.effect())) {
                grantCodes.add(item.code());
            } else if (SysUserPermission.EFFECT_DENY.equals(item.effect())) {
                denyCodes.add(item.code());
            } else {
                throw new BusinessException(400, "effect must be GRANT or DENY: " + item.effect());
            }
        }
        // 同 code GRANT + DENY 互斥（实现定死：拒绝）
        Set<String> conflicts = new HashSet<>(grantCodes);
        conflicts.retainAll(denyCodes);
        if (!conflicts.isEmpty()) {
            throw new BusinessException(400, "Conflicting GRANT and DENY for permission codes: " + conflicts);
        }
        // 防自锁守卫：不允许对超管账号 / 操作人本人 DENY role:manage
        if (denyCodes.contains(ROLE_MANAGE)) {
            SysUser target = userMapper.selectById(userId);
            if (target != null && rbacService.roleCodesForUser(userId).contains("ADMIN")) {
                throw new BusinessException(409, "Cannot DENY " + ROLE_MANAGE + " for an administrator account (anti-lockout)");
            }
            if (userId.equals(actorId)) {
                throw new BusinessException(409, "Cannot DENY " + ROLE_MANAGE + " for yourself (anti-lockout)");
            }
        }
    }

    private void requireUser(Long userId) {
        if (userMapper.selectById(userId) == null) {
            throw new BusinessException(404, "User not found");
        }
    }
}
