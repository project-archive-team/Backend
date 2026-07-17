package com.projectarchive.backend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 소스 수집에 재사용할 provider 액세스 토큰.
 * 로그인용이 아니라 GitHub/Drive/Notion API를 사용자 대신 호출하기 위한 것.
 */
@Entity
@Table(name = "oauth_tokens", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "provider"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OauthToken {

    public enum Provider { GITHUB, GOOGLE, NOTION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Provider provider;

    /** 암호화되어 저장된다. TokenCrypto 참고. */
    @Column(nullable = false, length = 4000)
    private String accessToken;

    @Column(length = 4000)
    private String refreshToken;

    private Instant expiresAt;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public OauthToken(User user, Provider provider, String accessToken, String refreshToken, Instant expiresAt) {
        this.user = user;
        this.provider = provider;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresAt = expiresAt;
    }

    public void update(String accessToken, String refreshToken, Instant expiresAt) {
        this.accessToken = accessToken;
        // provider가 refresh_token을 재발급 때 안 주는 경우가 있어 기존 값을 지우면 안 된다.
        if (refreshToken != null) {
            this.refreshToken = refreshToken;
        }
        this.expiresAt = expiresAt;
        this.updatedAt = Instant.now();
    }

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }
}
