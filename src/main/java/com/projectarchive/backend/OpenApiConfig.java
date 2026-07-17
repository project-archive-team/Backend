package com.projectarchive.backend;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * /swagger-ui.html 에서 확인. Authorize 버튼에 accessToken을 넣으면 보호된 API도 그대로 호출된다.
 * OAuth 로그인은 브라우저 리다이렉트라 Swagger에서 못 돌린다 — /api/auth/login으로 토큰을 받아 쓰면 된다.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(title = "Project Archive API", version = "v1",
                description = "프로젝트 아카이브 에이전트 백엔드. 수집·파싱·청킹·인증을 담당하고 RAG는 AI 서버에 위임한다."),
        security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        in = SecuritySchemeIn.HEADER)
public class OpenApiConfig {
}
