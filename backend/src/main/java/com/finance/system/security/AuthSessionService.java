package com.finance.system.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.finance.system.domain.entity.AuthSession;
import com.finance.system.domain.mapper.AuthSessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service
public class AuthSessionService {
    private final AuthSessionMapper mapper;
    public AuthSessionService(AuthSessionMapper mapper) { this.mapper = mapper; }
    @Transactional public void create(Long userId, String tokenId, int tokenVersion, LocalDateTime expiresAt) {
        AuthSession s = new AuthSession(); s.setUserId(userId); s.setTokenId(tokenId); s.setTokenVersion(tokenVersion); s.setExpiresAt(expiresAt); mapper.insert(s);
    }
    public boolean isActive(Long userId, String tokenId, int tokenVersion) {
        return userId != null && tokenId != null && mapper.selectCount(new LambdaQueryWrapper<AuthSession>()
                .eq(AuthSession::getUserId, userId).eq(AuthSession::getTokenId, tokenId).eq(AuthSession::getTokenVersion, tokenVersion)
                .isNull(AuthSession::getRevokedAt).gt(AuthSession::getExpiresAt, LocalDateTime.now())) == 1;
    }
    @Transactional public void revoke(Long userId, String tokenId) {
        mapper.update(null, new LambdaUpdateWrapper<AuthSession>().set(AuthSession::getRevokedAt, LocalDateTime.now())
                .eq(AuthSession::getUserId, userId).eq(AuthSession::getTokenId, tokenId).isNull(AuthSession::getRevokedAt));
    }

    /**
     * W10：单点登录支持 —— 撤销该用户除当前会话外的全部活跃会话（「新登录踢旧登录」）。
     *
     * <p>只撤销「未撤销且未过期」的行：已过期的行本就无法通过 isActive 校验，改动它们没有意义。
     * 调用点必须在<b>新会话落库之后</b>执行，否则会有「旧会话已踢、新会话尚未生效」的空窗。</p>
     *
     * @return 被踢下线的会话数（用于审计与登录响应提示）
     */
    @Transactional public int revokeOtherActive(Long userId, String keepTokenId) {
        return mapper.update(null, new LambdaUpdateWrapper<AuthSession>()
                .set(AuthSession::getRevokedAt, LocalDateTime.now())
                .eq(AuthSession::getUserId, userId)
                .isNull(AuthSession::getRevokedAt)
                .ne(keepTokenId != null, AuthSession::getTokenId, keepTokenId)
                .gt(AuthSession::getExpiresAt, LocalDateTime.now()));
    }
}
