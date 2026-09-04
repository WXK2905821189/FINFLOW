package com.finance.system.rbac;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finance.system.domain.entity.SysPermission;
import com.finance.system.domain.entity.SysRole;
import com.finance.system.domain.entity.SysRolePermission;
import com.finance.system.domain.entity.SysUserRole;
import com.finance.system.domain.mapper.SysPermissionMapper;
import com.finance.system.domain.mapper.SysRoleMapper;
import com.finance.system.domain.mapper.SysRolePermissionMapper;
import com.finance.system.domain.mapper.SysUserRoleMapper;
import com.finance.system.rbac.dto.RoleRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

@Service
public class RbacService {

    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;

    public RbacService(SysRoleMapper roleMapper,
                       SysPermissionMapper permissionMapper,
                       SysUserRoleMapper userRoleMapper,
                       SysRolePermissionMapper rolePermissionMapper) {
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
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

    public List<SysPermission> listPermissions() {
        return permissionMapper.selectList(new LambdaQueryWrapper<SysPermission>().orderByAsc(SysPermission::getId));
    }

    public Optional<SysRole> findRoleByCode(String code) {
        return Optional.ofNullable(roleMapper.selectOne(new LambdaQueryWrapper<SysRole>().eq(SysRole::getCode, code)));
    }

    @Transactional
    public SysRole createRole(RoleRequest request) {
        if (findRoleByCode(request.code()).isPresent()) {
            throw new com.finance.system.common.exception.BusinessException(409, "Role code already exists");
        }
        validatePermissionIds(request.permissionIds());
        SysRole role = new SysRole();
        role.setCode(request.code().trim().toUpperCase());
        role.setName(request.name().trim());
        role.setDescription(request.description());
        roleMapper.insert(role);
        request.permissionIds().stream().distinct()
                .forEach(permissionId -> rolePermissionMapper.insert(new SysRolePermission(role.getId(), permissionId)));
        return role;
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
