package com.finance.system.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("auth_session")
public class AuthSession {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    private Long userId; private String tokenId; private Integer tokenVersion; private LocalDateTime expiresAt; private LocalDateTime revokedAt; private LocalDateTime createdAt;
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getUserId() { return userId; } public void setUserId(Long v) { userId = v; }
    public String getTokenId() { return tokenId; } public void setTokenId(String v) { tokenId = v; }
    public Integer getTokenVersion() { return tokenVersion; } public void setTokenVersion(Integer v) { tokenVersion = v; }
    public LocalDateTime getExpiresAt() { return expiresAt; } public void setExpiresAt(LocalDateTime v) { expiresAt = v; }
    public LocalDateTime getRevokedAt() { return revokedAt; } public void setRevokedAt(LocalDateTime v) { revokedAt = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { createdAt = v; }
}
