package com.finance.system.rbac;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.audit.SystemAuditService;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.SysPermission;
import com.finance.system.domain.entity.SysRole;
import com.finance.system.domain.entity.SysRolePermission;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.entity.SysUserRole;
import com.finance.system.domain.mapper.SysPermissionMapper;
import com.finance.system.domain.mapper.SysRoleMapper;
import com.finance.system.domain.mapper.SysRolePermissionMapper;
import com.finance.system.domain.mapper.SysUserMapper;
import com.finance.system.domain.mapper.SysUserRoleMapper;
import com.finance.system.rbac.dto.RolePermissionsResponse;
import com.finance.system.rbac.dto.RoleRequest;
import com.finance.system.rbac.dto.RoleUpdateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class RbacService {

    /** Bootstrap roles (V1 seed). Kept for UI labelling: FINANCE_STAFF / FINANCE_MANAGER /
     * VIEWER are editable since V33; {@link #PROTECTED_ROLE_CODES} is the real immutability set. */
    public static final Set<String> BUILT_IN_ROLE_CODES = Set.of("ADMIN", "FINANCE_STAFF", "FINANCE_MANAGER", "VIEWER");

    /**
     * V33（2026-09-17）：仅 ADMIN 是安全锚点（持 role:manage/user:manage，保护自身可登录可管理），
     * 不可通过 API 修改；其余内置角色的权限集开放给超管可视化调整——角色管理页保存即生效
     * （权限每请求从库加载），并写入审计（ROLE_UPDATE）。基线矩阵见 docs/permission-catalog.md。
     */
    public static final Set<String> PROTECTED_ROLE_CODES = Set.of("ADMIN");

    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysUserMapper userMapper;
    private final SystemAuditService auditService;

    public RbacService(SysRoleMapper roleMapper,
                       SysPermissionMapper permissionMapper,
                       SysUserRoleMapper userRoleMapper,
                       SysRolePermissionMapper rolePermissionMapper,
                       SysUserMapper userMapper,
                       SystemAuditService auditService) {
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.userMapper = userMapper;
        this.auditService = auditService;
    }

    public List<SysRole> rolesForUser(Long userId) {
        List<Long> roleIds = userRoleMapper.findByUserId(userId)
                .stream().map(SysUserRole::getRoleId).toList();
        return roleIds.isEmpty() ? List.of() : roleMapper.selectByIds(roleIds);
    }

    public List<String> roleCodesForUser(Long userId) {
        return rolesForUser(userId).stream().map(SysRole::getCode).sorted().toList();
    }

    public List<SysPermission> permissionsForUser(Long userId) {
        List<Long> roleIds = rolesForUser(userId).stream().map(SysRole::getId).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        List<Long> permissionIds = rolePermissionMapper.findByRoleIds(roleIds)
                .stream().map(SysRolePermission::getPermissionId).distinct().toList();
        return permissionIds.isEmpty() ? List.of() : permissionMapper.selectByIds(permissionIds);
    }

    public List<String> permissionCodesForUser(Long userId) {
        return permissionsForUser(userId).stream().map(SysPermission::getCode).sorted().toList();
    }

    public List<String> authorityCodes(Long userId) {
        LinkedHashSet<String> authorities = new LinkedHashSet<>();
        roleCodesForUser(userId).forEach(role -> authorities.add("ROLE_" + role));
        authorities.addAll(permissionCodesForUser(userId));
        return List.copyOf(authorities);
    }

    public List<SysRole> listRoles() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId));
    }

    /** Roles with their permission id sets + member counts (grouped queries, ordered by role id). */
    public List<RolePermissionsResponse> listRolesWithPermissions() {
        List<SysRole> roles = listRoles();
        if (roles.isEmpty()) {
            return List.of();
        }
        List<Long> roleIds = roles.stream().map(SysRole::getId).toList();
        Map<Long, List<Long>> permissionIdsByRole = new java.util.HashMap<>();
        for (SysRolePermission relation : rolePermissionMapper.findByRoleIds(roleIds)) {
            permissionIdsByRole.computeIfAbsent(relation.getRoleId(), key -> new java.util.ArrayList<>())
                    .add(relation.getPermissionId());
        }
        Map<Long, Long> userCountsByRole = new java.util.HashMap<>();
        for (Long roleId : roleIds) {
            userCountsByRole.put(roleId, (long) userRoleMapper.findByRoleId(roleId).size());
        }
        return roles.stream().map(role -> new RolePermissionsResponse(role.getId(), role.getCode(), role.getName(),
                role.getDescription(), permissionIdsByRole.getOrDefault(role.getId(), List.of()),
                userCountsByRole.getOrDefault(role.getId(), 0L))).toList();
    }

    public List<SysPermission> listPermissions() {
        return permissionMapper.selectList(new LambdaQueryWrapper<SysPermission>().orderByAsc(SysPermission::getId));
    }

    public Optional<SysRole> findRoleByCode(String code) {
        return Optional.ofNullable(roleMapper.selectOne(new LambdaQueryWrapper<SysRole>().eq(SysRole::getCode, code)));
    }

    @Transactional
    public SysRole createRole(Long actorId, RoleRequest request) {
        if (findRoleByCode(request.code()).isPresent()) {
            throw new BusinessException(409, "Role code already exists");
        }
        validatePermissionIds(request.permissionIds());
        SysRole role = new SysRole();
        role.setCode(request.code().trim().toUpperCase());
        role.setName(request.name().trim());
        role.setDescription(request.description());
        roleMapper.insert(role);
        List<Long> permissionIds = request.permissionIds().stream().distinct().toList();
        permissionIds.forEach(permissionId -> rolePermissionMapper.insert(new SysRolePermission(role.getId(), permissionId)));
        auditService.record(actorId, "ROLE_CREATE", "ROLE", role.getCode(), null, "SUCCESS", "permissions=" + permissionIds);
        return role;
    }

    /**
     * GAP-6 + V33: adjust name/description/permission set of a role. Since V33 only the ADMIN
     * role is protected (安全锚点：持 role:manage 的超管角色，防止把系统改到无人可管理);
     * FINANCE_STAFF / FINANCE_MANAGER / VIEWER and custom roles are editable from the UI.
     * Permission changes take effect on the next request because authorities are reloaded
     * from the database per request (UserDetailsServiceImpl), so no token invalidation is needed.
     */
    @Transactional
    public SysRole updateRole(Long actorId, Long roleId, RoleUpdateRequest request) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException(404, "Role not found");
        }
        if (PROTECTED_ROLE_CODES.contains(role.getCode())) {
            throw new BusinessException(400, "ADMIN role is protected and cannot be modified");
        }
        List<Long> before = rolePermissionMapper.findByRoleIds(List.of(roleId)).stream()
                .map(SysRolePermission::getPermissionId).distinct().sorted().toList();
        validatePermissionIds(request.permissionIds());
        role.setName(request.name().trim());
        role.setDescription(request.description());
        roleMapper.updateById(role);
        rolePermissionMapper.deleteByRoleId(roleId);
        List<Long> after = request.permissionIds().stream().distinct().toList();
        after.forEach(permissionId -> rolePermissionMapper.insert(new SysRolePermission(roleId, permissionId)));
        auditService.record(actorId, "ROLE_UPDATE", "ROLE", role.getCode(), null, "SUCCESS",
                "permissions " + before + " -> " + after);
        return role;
    }

    /**
     * GAP-2 guard helper: number of ACTIVE users holding the ADMIN role, optionally
     * excluding one user. Used to refuse demotion/disabling of the last active administrator.
     */
    public long countActiveAdminsExcluding(Long excludeUserId) {
        SysRole adminRole = findRoleByCode("ADMIN").orElse(null);
        if (adminRole == null) {
            return 0;
        }
        List<Long> userIds = userRoleMapper.findByRoleId(adminRole.getId()).stream()
                .map(SysUserRole::getUserId).distinct().toList();
        if (userIds.isEmpty()) {
            return 0;
        }
        LambdaQueryWrapper<SysUser> query = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStatus, "ACTIVE")
                .in(SysUser::getId, userIds);
        if (excludeUserId != null) {
            query.ne(SysUser::getId, excludeUserId);
        }
        return userMapper.selectCount(query);
    }

    @Transactional
    public void replaceUserRoles(Long userId, Collection<Long> roleIds) {
        validateRoleIds(roleIds);
        userRoleMapper.deleteByUserId(userId);
        roleIds.stream().distinct().forEach(roleId -> userRoleMapper.insert(new SysUserRole(userId, roleId)));
    }

    private void validateRoleIds(Collection<Long> roleIds) {
        List<Long> distinctIds = roleIds.stream().distinct().toList();
        if (distinctIds.isEmpty() || roleMapper.selectByIds(distinctIds).size() != distinctIds.size()) {
            throw new com.finance.system.common.exception.BusinessException(400, "One or more roles do not exist");
        }
    }

    private void validatePermissionIds(Collection<Long> permissionIds) {
        List<Long> distinctIds = permissionIds.stream().distinct().toList();
        if (distinctIds.isEmpty() || permissionMapper.selectByIds(distinctIds).size() != distinctIds.size()) {
            throw new com.finance.system.common.exception.BusinessException(400, "One or more permissions do not exist");
        }
    }
}
