package com.projectarchive.backend.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * SecurityConfig와 분리해야 한다: SecurityConfig는 OAuth2SuccessHandler를 주입받는데
 * 그 핸들러가 TextEncryptor를 필요로 해서, 같은 클래스에 두면 순환 참조가 된다.
 */
@Configuration
public class CryptoConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** provider 액세스 토큰 저장용. AES-256-GCM. */
    @Bean
    TextEncryptor tokenEncryptor(@Value("${app.crypto.password}") String password,
                                 @Value("${app.crypto.salt}") String salt) {
        return Encryptors.delux(password, salt);
    }
}
