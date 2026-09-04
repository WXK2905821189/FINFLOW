package com.finance.system.security;

import com.finance.system.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
    }

    public String generateToken(UserPrincipal principal) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(principal.getUsername())
                .claim("uid", principal.getId())
                .claim("ver", principal.getTokenVersion())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.getExpiration())))
                .signWith(signingKey())
                .compact();
    }

    public String extractUsername(String token) {
        return claims(token).getSubject();
    }

    public boolean isValid(String token, UserPrincipal principal) {
        Claims claims = claims(token);
        return principal.getUsername().equals(claims.getSubject())
                && claims.getExpiration().after(new Date())
                && principal.isEnabled();
    }

    public long expirationSeconds() {
        return properties.getExpiration().toSeconds();
    }

    public String extractTokenId(String token) { return claims(token).getId(); }
    public Long extractUserId(String token) { return claims(token).get("uid", Long.class); }
    public int extractTokenVersion(String token) { Integer version = claims(token).get("ver", Integer.class); return version == null ? -1 : version; }
    public LocalDateTime extractExpiration(String token) { return LocalDateTime.ofInstant(claims(token).getExpiration().toInstant(), ZoneId.systemDefault()); }

    private Claims claims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey signingKey() {
        String secret = properties.getSecret();
        byte[] key = secret.startsWith("base64:")
                ? Decoders.BASE64.decode(secret.substring("base64:".length()))
                : secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(key);
    }
}
