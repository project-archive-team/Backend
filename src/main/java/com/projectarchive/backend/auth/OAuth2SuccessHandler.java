package com.projectarchive.backend.auth;

import com.projectarchive.backend.domain.OauthToken;
import com.projectarchive.backend.domain.User;
import com.projectarchive.backend.repo.OauthTokenRepository;
import com.projectarchive.backend.repo.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * OAuth 로그인 성공 시: 계정을 찾거나 만들고, provider 액세스 토큰을 수집용으로 저장한 뒤,
 * 우리 JWT를 붙여 프론트로 리다이렉트한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository users;
    private final OauthTokenRepository tokens;
    private final OAuth2AuthorizedClientService authorizedClients;
    private final JwtService jwtService;
    private final TextEncryptor encryptor;

    @Value("${app.oauth.redirect-uri}")
    private String redirectUri;

    @Override
    @Transactional
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {

        var oauthToken = (OAuth2AuthenticationToken) authentication;
        String registrationId = oauthToken.getAuthorizedClientRegistrationId();
        OAuth2User principal = oauthToken.getPrincipal();

        var client = authorizedClients.loadAuthorizedClient(registrationId, oauthToken.getName());
        String email = resolveEmail(registrationId, principal, client);
        String name = resolveName(registrationId, principal);

        User user = users.findByEmail(email).orElseGet(() -> users.save(User.oauthOnly(email, name)));

        saveProviderToken(user, registrationId, client);

        String jwt = jwtService.issueAccess(user.getId());
        String refresh = jwtService.issueRefresh(user.getId());
        String target = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("accessToken", jwt)
                .queryParam("refreshToken", refresh)
                .build().toUriString();
        response.sendRedirect(target);
    }

    private void saveProviderToken(User user, String registrationId, OAuth2AuthorizedClient client) {
        if (client == null) {
            return;
        }
        var provider = OauthToken.Provider.valueOf(registrationId.toUpperCase());
        var access = encryptor.encrypt(client.getAccessToken().getTokenValue());
        var refresh = client.getRefreshToken() == null ? null : encryptor.encrypt(client.getRefreshToken().getTokenValue());
        var expiresAt = client.getAccessToken().getExpiresAt();

        tokens.findByUserIdAndProvider(user.getId(), provider)
                .ifPresentOrElse(
                        t -> t.update(access, refresh, expiresAt),
                        () -> tokens.save(new OauthToken(user, provider, access, refresh, expiresAt)));
    }

    private String resolveEmail(String registrationId, OAuth2User principal, OAuth2AuthorizedClient client) {
        String email = principal.getAttribute("email");
        if (email != null) {
            return email;
        }
        if ("github".equals(registrationId)) {
            // 이메일이 비공개면 attribute에 안 실린다. noreply 주소로 때우면 기존 계정과 이메일이 어긋나
            // 같은 사람에게 계정이 하나 더 생긴다 — /user/emails로 실제 주소를 확인한다.
            String primary = githubPrimaryEmail(client);
            return primary != null
                    ? primary
                    : principal.getAttribute("id") + "+" + principal.getAttribute("login") + "@users.noreply.github.com";
        }
        throw new IllegalStateException("no email from provider: " + registrationId);
    }

    private String githubPrimaryEmail(OAuth2AuthorizedClient client) {
        if (client == null) {
            return null;
        }
        try {
            JsonNode emails = RestClient.create().get()
                    .uri("https://api.github.com/user/emails")
                    .header("Authorization", "Bearer " + client.getAccessToken().getTokenValue())
                    .header("Accept", "application/vnd.github+json")
                    .retrieve().body(JsonNode.class);
            for (JsonNode e : emails) {
                if (e.path("primary").asBoolean() && e.path("verified").asBoolean()) {
                    return e.path("email").asString(null);
                }
            }
        } catch (Exception e) {
            // user:email 스코프가 없거나 API가 막히면 noreply 주소로 떨어진다.
            log.warn("github 기본 이메일 조회 실패: {}", e.getMessage());
        }
        return null;
    }

    private String resolveName(String registrationId, OAuth2User principal) {
        String name = principal.getAttribute("name");
        if (name != null) {
            return name;
        }
        String login = principal.getAttribute("login");
        return login != null ? login : "unknown";
    }
}
