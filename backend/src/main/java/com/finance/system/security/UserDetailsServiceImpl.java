package com.finance.system.security;

import com.finance.system.domain.service.RbacService;
import com.finance.system.domain.service.SysUserService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final SysUserService userService;
    private final RbacService rbacService;

    public UserDetailsServiceImpl(SysUserService userService, RbacService rbacService) {
        this.userService = userService;
        this.rbacService = rbacService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var user = userService.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Account or password is invalid"));
        return new UserPrincipal(user, rbacService.authorityCodes(user.getId()));
    }
}
