package com.projectarchive.backend.collect;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "owner/name"을 URI 변수 하나로 넘기면 RestClient가 슬래시를 %2F로 인코딩해
 * /repos/owner%2Fname 이 되고 GitHub이 404를 돌려준다. 실제로 나갔던 버그라 경로를 고정해 둔다.
 */
class GithubUriEncodingTest {

    /** 요청을 실제로 보내지 않고, 만들어진 URI만 가로채서 확인한다. */
    private URI buildUri(String template, Object... vars) {
        var captured = new AtomicReference<URI>();
        RestClient client = RestClient.builder()
                .baseUrl("https://api.github.com")
                .requestInterceptor((request, body, execution) -> {
                    captured.set(request.getURI());
                    throw new StopHere();
                })
                .build();
        try {
            client.get().uri(template, vars).retrieve().toBodilessEntity();
        } catch (Exception ignored) {
            // 인터셉터가 일부러 끊는다.
        }
        return captured.get();
    }

    @Test
    void repositoryPathKeepsTheSlashBetweenOwnerAndName() {
        URI uri = buildUri("/repos/{owner}/{name}/commits?per_page=100", "project-archive-team", "Backend");

        assertThat(uri.toString())
                .isEqualTo("https://api.github.com/repos/project-archive-team/Backend/commits?per_page=100");
    }

    @Test
    void slashInsideOneVariableGetsEncodedAndBreaksTheCall() {
        URI uri = buildUri("/repos/{repo}/commits", "project-archive-team/Backend");

        // 이렇게 부르면 안 된다는 걸 남겨둔다 — GitHub은 이 경로에 404를 준다.
        assertThat(uri.toString()).contains("%2F");
    }

    private static final class StopHere extends RuntimeException {
    }
}
