package com.projectarchive.backend.collect;

import com.projectarchive.backend.domain.OauthToken;
import com.projectarchive.backend.domain.User;
import com.projectarchive.backend.repo.OauthTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TokenStore {

    private final OauthTokenRepository tokens;
    private final TextEncryptor encryptor;

    @Transactional(readOnly = true)
    public Optional<String> accessToken(Long userId, OauthToken.Provider provider) {
        return tokens.findByUserIdAndProvider(userId, provider)
                .map(t -> encryptor.decrypt(t.getAccessToken()));
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
