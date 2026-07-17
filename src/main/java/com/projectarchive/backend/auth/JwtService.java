package com.projectarchive.backend.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.access-ttl:PT1H}") Duration accessTtl,
                      @Value("${app.jwt.refresh-ttl:P14D}") Duration refreshTtl) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        // HS256은 256비트 이상을 요구한다. 짧은 시크릿이 설정되면 부팅 시점에 죽는 게 낫다.
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    public String issueAccess(Long userId) {
        return issue(userId, "access", accessTtl);
    }

    public String issueRefresh(Long userId) {
        return issue(userId, "refresh", refreshTtl);
    }

    private String issue(Long userId, String type, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** 서명/만료가 유효한 access 토큰이면 userId, 아니면 예외. */
    public Long parseAccess(String token) {
        return parse(token, "access");
    }

    public Long parseRefresh(String token) {
        return parse(token, "refresh");
    }

    private Long parse(String token, String expectedType) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        // refresh 토큰으로 API를 호출하는 걸 막는다.
        if (!expectedType.equals(claims.get("type", String.class))) {
            throw new IllegalArgumentException("wrong token type");
        }
        return Long.valueOf(claims.getSubject());
    }

    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }
}
