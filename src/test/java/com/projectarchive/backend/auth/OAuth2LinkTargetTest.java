package com.projectarchive.backend.auth;

import com.projectarchive.backend.domain.User;
import com.projectarchive.backend.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 가입 이메일과 GitHub 이메일이 달라 같은 사람에게 계정이 두 개 생기던 문제를 막는 경로.
 * 로그인 상태에서 연결을 시작했으면 이메일과 무관하게 그 계정에 붙어야 한다.
 */
class OAuth2LinkTargetTest {

    private final UserRepository users = mock(UserRepository.class);
    private final OAuth2SuccessHandler handler =
            new OAuth2SuccessHandler(users, null, null, null, null);

    @Test
    void usesTheLoggedInAccountWhenLinkIntentIsPresent() {
        User existing = User.withPassword("mi0614@gachon.ac.kr", "hash", "친구");
        when(users.findById(8L)).thenReturn(Optional.of(existing));

        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(AuthController.LINK_USER_ID, 8L);

        assertThat(handler.linkTarget(request)).contains(existing);
    }

    @Test
    void consumesTheIntentSoTheNextSocialLoginIsNotHijacked() {
        when(users.findById(any())).thenReturn(Optional.of(User.oauthOnly("a@b.com", "x")));

        var request = new MockHttpServletRequest();
        var session = request.getSession();
        session.setAttribute(AuthController.LINK_USER_ID, 8L);

        handler.linkTarget(request);

        assertThat(session.getAttribute(AuthController.LINK_USER_ID)).isNull();
        assertThat(handler.linkTarget(request)).isEmpty();
    }

    @Test
    void fallsBackToEmailLookupWhenThereIsNoSession() {
        // 세션이 아예 없으면 평범한 소셜 로그인이다.
        assertThat(handler.linkTarget(new MockHttpServletRequest())).isEmpty();
    }
}
