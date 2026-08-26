package com.finance.system.domain.service;

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

import java.util.Optional;

@Service
public class SysUserService extends ServiceImpl<SysUserMapper, SysUser> {

    private final PasswordEncoder passwordEncoder;
    private final RbacService rbacService;

    public SysUserService(PasswordEncoder passwordEncoder, RbacService rbacService) {
        this.passwordEncoder = passwordEncoder;
        this.rbacService = rbacService;
    }

    public Optional<SysUser> findByUsername(String username) {
        return Optional.ofNullable(baseMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)));
    }

    public Optional<SysUser> findByEmail(String email) {
        return Optional.ofNullable(baseMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmail, email)));
    }

    @Transactional
    public SysUser register(RegisterRequest request) {
        ensureIdentityAvailable(request.username(), request.email(), null);
        SysUser user = new SysUser();
        user.setUsername(request.username().trim());
        user.setEmail(request.email().trim().toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStatus("PENDING");
        save(user);
        return user;
    }

    @Transactional
    public SysUser create(UserUpsertRequest request) {
        ensureIdentityAvailable(request.username(), request.email(), null);
        if (request.password() == null || request.password().length() < 8) {
            throw new BusinessException(400, "An initial password of at least 8 characters is required");
        }
        SysUser user = new SysUser();
        copyRequest(request, user);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        save(user);
        rbacService.replaceUserRoles(user.getId(), request.roleIds());
        return user;
    }

    @Transactional
    public SysUser updateUser(Long id, UserUpsertRequest request) {
        SysUser user = getById(id);
        if (user == null) {
            throw new BusinessException(404, "User not found");
        }
        ensureIdentityAvailable(request.username(), request.email(), id);
        copyRequest(request, user);
        if (request.password() != null && !request.password().isBlank()) {
            if (request.password().length() < 8) {
                throw new BusinessException(400, "Password must be at least 8 characters");
            }
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        updateById(user);
        rbacService.replaceUserRoles(id, request.roleIds());
        return user;
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
