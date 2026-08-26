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
import java.util.Date;

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
