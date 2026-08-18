package org.xxg.backend.backend.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtUtil {

    private final Key secretKey;
    private final long expirationTime;
    private final long refreshExpirationTime;

    public JwtUtil(
            @Value("${jwt.secret:${JWT_SECRET:}}") String secret,
            @Value("${jwt.access-expiration-ms:3600000}") long expirationTime,
            @Value("${jwt.refresh-expiration-ms:604800000}") long refreshExpirationTime) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("JWT_SECRET must be configured and contain at least 32 characters");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationTime = expirationTime;
        this.refreshExpirationTime = refreshExpirationTime;
    }

    public String generateToken(String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        claims.put("type", "access");
        return createToken(claims, username, expirationTime);
    }

    public String generateRefreshToken(String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        claims.put("type", "refresh");
        return createToken(claims, username, refreshExpirationTime);
    }

    private String createToken(Map<String, Object> claims, String subject, long expiration) {
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public boolean validateToken(String token, String username) {
        try {
            final String extractedUsername = extractUsername(token);
            return (extractedUsername.equals(username) && !isTokenExpired(token));
        } catch (Exception e) {
            return false;
        }
    }

    public boolean validateRefreshToken(String token, String username) {
        try {
            final String extractedUsername = extractUsername(token);
            String type = (String) extractAllClaims(token).get("type");
            return (extractedUsername.equals(username) && !isTokenExpired(token) && "refresh".equals(type));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 校验第三方登录的「注册/绑定」专用临时令牌。
     * 该令牌由 OAuthService 在回调时签发，subject 固定为 "register" 且携带 type=register、
     * socialUid、socialType 等 claims；普通 access/refresh 令牌一律拒绝，防止令牌用途混用。
     */
    public boolean validateRegisterToken(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return "register".equals(claims.getSubject())
                    && "register".equals(claims.get("type"))
                    && claims.get("socialUid") != null
                    && claims.get("socialType") != null
                    && !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }

    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }
    
    public String extractRole(String token) {
        return (String) extractAllClaims(token).get("role");
    }

    public String generateCustomToken(Map<String, Object> claims, String subject, long expirationSeconds) {
        return createToken(claims, subject, expirationSeconds * 1000);
    }
    
    public Claims extractAllClaims(String token) {
        return Jwts.parserBuilder().setSigningKey(secretKey).build().parseClaimsJws(token).getBody();
    }

    private boolean isTokenExpired(String token) {
        return extractAllClaims(token).getExpiration().before(new Date());
    }
}
