package com.projectarchive.backend.auth;

import com.projectarchive.backend.domain.OauthToken;
import com.projectarchive.backend.domain.User;
import com.projectarchive.backend.repo.OauthTokenRepository;
import com.projectarchive.backend.repo.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
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

        String email = resolveEmail(registrationId, principal);
        String name = resolveName(registrationId, principal);

        User user = users.findByEmail(email).orElseGet(() -> users.save(User.oauthOnly(email, name)));

        saveProviderToken(user, registrationId, oauthToken);

        String jwt = jwtService.issueAccess(user.getId());
        String refresh = jwtService.issueRefresh(user.getId());
        String target = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("accessToken", jwt)
                .queryParam("refreshToken", refresh)
                .build().toUriString();
        response.sendRedirect(target);
    }

    private void saveProviderToken(User user, String registrationId, OAuth2AuthenticationToken auth) {
        var client = authorizedClients.loadAuthorizedClient(registrationId, auth.getName());
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

    private String resolveEmail(String registrationId, OAuth2User principal) {
        String email = principal.getAttribute("email");
        if (email != null) {
            return email;
        }
        // ponytail: GitHub은 이메일이 비공개면 attribute에 안 실린다. /user/emails를 부르는 대신
        // GitHub이 실제로 발급하는 noreply 주소 규칙을 쓴다. 이메일 발송이 필요해지면 그때 API 호출로 교체.
        if ("github".equals(registrationId)) {
            return principal.getAttribute("id") + "+" + principal.getAttribute("login") + "@users.noreply.github.com";
        }
        throw new IllegalStateException("no email from provider: " + registrationId);
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
