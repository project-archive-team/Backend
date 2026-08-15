package com.projectarchive.backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 허용 메서드에 PUT이 빠져 있어 Notion 토큰 저장(API 유일의 PUT)만 403 "Invalid CORS request"로 막혔다.
 * GET은 같은 오리진이면 Origin 헤더가 없어 검사를 안 타고, POST는 목록에 있어서 아무도 눈치채지 못했다.
 */
class CorsMethodTest {

    private final CorsConfiguration config = new SecurityConfig(null, null)
            .corsConfigurationSource()
            .getCorsConfiguration(new MockHttpServletRequest("PUT", "/api/integrations/notion"));

    @Test
    void everyMethodTheApiActuallyUsesPassesTheCorsCheck() {
        assertThat(config).isNotNull();
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
                HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS)) {
            assertThat(config.checkHttpMethod(method))
                    .as("%s 요청이 CORS에서 막히면 안 된다", method)
                    .isNotEmpty();
        }
    }

    @Test
    void credentialsStayOnSoTheLinkIntentSessionCookieSurvives() {
        assertThat(config.getAllowCredentials()).isTrue();
    }
}
