package com.projectarchive.backend.collect;

import com.projectarchive.backend.auth.CurrentUserId;
import com.projectarchive.backend.domain.OauthToken;
import com.projectarchive.backend.repo.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * GitHub/Google은 OAuth 로그인에서 토큰이 자동으로 들어온다.
 * Notion만 사용자가 integration token을 직접 붙여넣는다.
 */
@RestController
@RequestMapping("/api/integrations")
@RequiredArgsConstructor
public class IntegrationController {

    private final TokenStore tokenStore;
    private final UserRepository users;

    public record NotionTokenRequest(@NotBlank String token) {}

    @PutMapping("/notion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void connectNotion(@CurrentUserId Long userId, @Valid @RequestBody NotionTokenRequest req) {
        var user = users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        tokenStore.put(user, OauthToken.Provider.NOTION, req.token());
    }

    /** 어떤 provider가 연결돼 있는지 — 소스 연결 화면의 체크 표시용. */
    @GetMapping
    public java.util.Map<String, Boolean> connected(@CurrentUserId Long userId) {
        var out = new java.util.LinkedHashMap<String, Boolean>();
        for (var p : OauthToken.Provider.values()) {
            out.put(p.name().toLowerCase(), tokenStore.accessToken(userId, p).isPresent());
        }
        return out;
    }
}
