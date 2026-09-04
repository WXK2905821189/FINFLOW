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
}
