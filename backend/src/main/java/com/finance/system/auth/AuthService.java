package com.finance.system.auth;

import com.finance.system.auth.dto.AuthTokenResponse;
import com.finance.system.auth.dto.CurrentUserResponse;
import com.finance.system.auth.dto.LoginRequest;
import com.finance.system.auth.dto.RegisterRequest;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.domain.service.RbacService;
import com.finance.system.domain.service.SysUserService;
import com.finance.system.security.JwtService;
import com.finance.system.security.UserPrincipal;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final SysUserService userService;
    private final RbacService rbacService;

    public AuthService(AuthenticationManager authenticationManager,
                       JwtService jwtService,
                       SysUserService userService,
                       RbacService rbacService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userService = userService;
        this.rbacService = rbacService;
    }

    public AuthTokenResponse login(LoginRequest request) {
        try {
            UserPrincipal principal = (UserPrincipal) authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())).getPrincipal();
            SysUser user = userService.getById(principal.getId());
            return new AuthTokenResponse(
                    jwtService.generateToken(principal),
                    "Bearer",
                    jwtService.expirationSeconds(),
                    currentUser(user)
            );
        } catch (AuthenticationException exception) {
            throw new BusinessException(401, "Account or password is invalid");
        }
    }

    public CurrentUserResponse currentUser(UserPrincipal principal) {
        SysUser user = userService.getById(principal.getId());
        if (user == null) {
            throw new BusinessException(401, "Authentication is required");
        }
        return currentUser(user);
    }

    public CurrentUserResponse currentUser(SysUser user) {
        return new CurrentUserResponse(
                user.getId(), user.getUsername(), user.getEmail(), user.getPhone(), user.getStatus(),
                rbacService.roleCodesForUser(user.getId()),
                rbacService.permissionCodesForUser(user.getId())
        );
    }

    public CurrentUserResponse register(RegisterRequest request) {
        return currentUser(userService.register(request));
    }
}
