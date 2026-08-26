package com.finance.system.domain.service;

import com.finance.system.domain.entity.SysRole;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.mapper.SysUserMapper;
import com.finance.system.domain.mapper.SysUserRoleMapper;
import com.finance.system.domain.entity.SysUserRole;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BootstrapDataInitializer implements ApplicationRunner {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final RbacService rbacService;
    private final PasswordEncoder passwordEncoder;

    public BootstrapDataInitializer(SysUserMapper userMapper,
                                   SysUserRoleMapper userRoleMapper,
                                   RbacService rbacService,
                                   PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.rbacService = rbacService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "admin")) > 0) {
            return;
        }
        SysRole adminRole = rbacService.findRoleByCode("ADMIN").orElseThrow(
                () -> new IllegalStateException("Flyway RBAC seed data is missing"));
        SysUser admin = new SysUser();
        admin.setUsername("admin");
        admin.setEmail("admin@finflow.local");
        admin.setPasswordHash(passwordEncoder.encode("Admin@123"));
        admin.setStatus("ACTIVE");
        userMapper.insert(admin);
        userRoleMapper.insert(new SysUserRole(admin.getId(), adminRole.getId()));
    }
}
