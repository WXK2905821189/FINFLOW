package com.finance.system.auth;

import com.finance.system.audit.SystemAuditService;
import com.finance.system.auth.dto.AuthTokenResponse;
import com.finance.system.auth.dto.CurrentUserResponse;
import com.finance.system.auth.dto.LoginRequest;
import com.finance.system.auth.dto.RegisterRequest;
import com.finance.system.common.exception.BusinessException;
import com.finance.system.domain.entity.SysUser;
import com.finance.system.rbac.RbacService;
import com.finance.system.user.SysUserService;
import com.finance.system.security.JwtService;
import com.finance.system.security.AuthSessionService;
import com.finance.system.security.LoginThrottleService;
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
    private final AuthSessionService authSessionService;
    private final LoginThrottleService throttleService;
    private final SystemAuditService auditService;

    public AuthService(AuthenticationManager authenticationManager,
                       JwtService jwtService,
                       SysUserService userService,
                       RbacService rbacService,
                       AuthSessionService authSessionService,
                       LoginThrottleService throttleService,
                       SystemAuditService auditService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userService = userService;
        this.rbacService = rbacService;
        this.authSessionService = authSessionService;
        this.throttleService = throttleService;
        this.auditService = auditService;
    }

    public AuthTokenResponse login(LoginRequest request, String clientIp) {
        // GAP-4: IP-level failure throttle first (cheap), then username lockout. The
        // lockout reuses the generic 401 so it cannot leak whether an account exists.
        throttleService.ensureIpAllowed(clientIp);
        throttleService.ensureUsernameNotLocked(request.username());
        try {
            UserPrincipal principal = (UserPrincipal) authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())).getPrincipal();
            SysUser user = userService.getById(principal.getId());
            String token = jwtService.generateToken(principal);
            authSessionService.create(user.getId(), jwtService.extractTokenId(token), principal.getTokenVersion(),
                    jwtService.extractExpiration(token));
            throttleService.recordSuccess(request.username());
            auditService.record(user.getId(), "LOGIN_SUCCESS", "AUTH", user.getUsername(), null, "SUCCESS", "ip=" + clientIp);
            return new AuthTokenResponse(
                    token,
                    "Bearer",
                    jwtService.expirationSeconds(),
                    currentUser(user)
            );
        } catch (AuthenticationException exception) {
            throttleService.recordFailure(request.username(), clientIp);
            auditService.record(null, "LOGIN_FAIL", "AUTH", request.username(), null, "FAILURE", "ip=" + clientIp);
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

    public void logout(UserPrincipal principal, String token) {
        if (principal == null || token == null || token.isBlank()) throw new BusinessException(401, "Authentication is required");
        authSessionService.revoke(principal.getId(), jwtService.extractTokenId(token));
        auditService.record(principal.getId(), "LOGOUT", "AUTH", principal.getUsername(), null, "SUCCESS", null);
    }
}
