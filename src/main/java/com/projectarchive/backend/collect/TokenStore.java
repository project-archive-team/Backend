package com.projectarchive.backend.collect;

import com.projectarchive.backend.domain.OauthToken;
import com.projectarchive.backend.domain.User;
import com.projectarchive.backend.repo.OauthTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Optional;

@Service
@Slf4j
public class TokenStore {

    /** 만료 직전에 꺼내 쓰면 수집 도중 죽는다. 이만큼 남았으면 미리 갱신한다. */
    private static final long REFRESH_SKEW_SECONDS = 120;

    private final OauthTokenRepository tokens;
    private final TextEncryptor encryptor;
    private final String googleClientId;
    private final String googleClientSecret;

    private final RestClient http = RestClient.builder()
            .baseUrl("https://oauth2.googleapis.com")
            .build();

    public TokenStore(OauthTokenRepository tokens, TextEncryptor encryptor,
                      @Value("${spring.security.oauth2.client.registration.google.client-id}") String googleClientId,
                      @Value("${spring.security.oauth2.client.registration.google.client-secret}") String googleClientSecret) {
        this.tokens = tokens;
        this.encryptor = encryptor;
        this.googleClientId = googleClientId;
        this.googleClientSecret = googleClientSecret;
    }

    /**
     * 수집기가 쓰는 진입점. Google 액세스 토큰은 1시간짜리라 만료됐으면 refresh token으로 갱신한다.
     * GitHub OAuth App 토큰은 만료가 없어 그대로 돌려준다.
     */
    @Transactional
    public Optional<String> accessToken(Long userId, OauthToken.Provider provider) {
        return tokens.findByUserIdAndProvider(userId, provider)
                .map(token -> needsRefresh(token) ? refreshed(token) : encryptor.decrypt(token.getAccessToken()));
    }

    private boolean needsRefresh(OauthToken token) {
        return token.getProvider() == OauthToken.Provider.GOOGLE
                && token.getRefreshToken() != null
                && token.getExpiresAt() != null
                && token.getExpiresAt().minusSeconds(REFRESH_SKEW_SECONDS).isBefore(Instant.now());
    }

    /** 갱신에 실패하면 옛 토큰을 그대로 준다 — 수집기가 401을 받고 소스만 FAILED로 남는 편이 낫다. */
    private String refreshed(OauthToken token) {
        try {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("client_id", googleClientId);
            form.add("client_secret", googleClientSecret);
            form.add("refresh_token", encryptor.decrypt(token.getRefreshToken()));
            form.add("grant_type", "refresh_token");

            JsonNode res = http.post().uri("/token").body(form).retrieve().body(JsonNode.class);
            String access = res.path("access_token").asString(null);
            if (access == null) {
                throw new IllegalStateException("no access_token in refresh response");
            }
            Instant expiresAt = Instant.now().plusSeconds(res.path("expires_in").asLong(3600));
            // Google은 갱신 응답에 refresh_token을 다시 주지 않는다 — update가 기존 값을 지키게 null을 넘긴다.
            token.update(encryptor.encrypt(access), null, expiresAt);
            return access;
        } catch (Exception e) {
            log.warn("google 토큰 갱신 실패 (user {})", token.getUser().getId(), e);
            return encryptor.decrypt(token.getAccessToken());
        }
    }

    @Transactional
    public void remove(Long userId, OauthToken.Provider provider) {
        tokens.findByUserIdAndProvider(userId, provider).ifPresent(tokens::delete);
    }

    /**
     * Notion용. 사용자가 internal integration token을 붙여넣는 경로.
     * ponytail: Notion OAuth 앱 등록 대신 이걸로 시작한다. 워크스페이스 여러 개를 붙여야 하면 OAuth로 교체.
     */
    @Transactional
    public void put(User user, OauthToken.Provider provider, String rawToken) {
        String encrypted = encryptor.encrypt(rawToken);
        tokens.findByUserIdAndProvider(user.getId(), provider)
                .ifPresentOrElse(
                        t -> t.update(encrypted, null, null),
                        () -> tokens.save(new OauthToken(user, provider, encrypted, null, null)));
    }
}
