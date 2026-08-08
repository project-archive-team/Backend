package com.projectarchive.backend.collect;

import com.projectarchive.backend.domain.Artifact;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubCollectorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void mergedPullRequestKeepsBodyAndMarksMerged() {
        var pr = JSON.readTree("""
                {
                  "number": 12,
                  "title": "AI 호출 HTTP/1.1 고정",
                  "body": "h2c 업그레이드 때문에 본문이 유실됨",
                  "state": "closed",
                  "merged_at": "2026-07-30T10:00:00Z",
                  "created_at": "2026-07-29T09:00:00Z",
                  "user": {"login": "worhs02"},
                  "html_url": "https://github.com/o/r/pull/12"
                }
                """);

        RawItem item = GithubCollector.toPullRequestItem(pr);

        assertThat(item.type()).isEqualTo(Artifact.Type.DOC);
        // 커밋 sha와 같은 네임스페이스를 쓰면 upsert가 충돌한다.
        assertThat(item.externalId()).isEqualTo("pr-12");
        assertThat(item.title()).isEqualTo("PR #12 AI 호출 HTTP/1.1 고정");
        assertThat(item.content()).contains("상태: merged").contains("h2c 업그레이드");
        assertThat(item.author()).isEqualTo("worhs02");
        assertThat(item.occurredAt()).isEqualTo(Instant.parse("2026-07-29T09:00:00Z"));
    }

    @Test
    void openPullRequestWithoutBodyStillProducesContent() {
        var pr = JSON.readTree("""
                {"number": 3, "title": "WIP", "body": null, "state": "open",
                 "merged_at": null, "created_at": "2026-08-01T00:00:00Z",
                 "user": {"login": "x"}, "html_url": "u"}
                """);

        RawItem item = GithubCollector.toPullRequestItem(pr);

        assertThat(item.content()).contains("상태: open");
        assertThat(item.content()).doesNotContain("null");
    }

    @Test
    void normalizesTheFormsUsersActuallyPaste() {
        assertThat(GithubCollector.normalizeRepo("owner/repo")).isEqualTo("owner/repo");
        assertThat(GithubCollector.normalizeRepo("https://github.com/owner/repo")).isEqualTo("owner/repo");
        assertThat(GithubCollector.normalizeRepo("https://github.com/owner/repo.git")).isEqualTo("owner/repo");
        assertThat(GithubCollector.normalizeRepo("http://github.com/owner/repo/")).isEqualTo("owner/repo");
        assertThat(GithubCollector.normalizeRepo("  owner/repo  ")).isEqualTo("owner/repo");
        // 브랜치까지 붙여넣은 URL도 저장소까지만 남긴다.
        assertThat(GithubCollector.normalizeRepo("https://github.com/owner/repo/tree/main")).isEqualTo("owner/repo");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> GithubCollector.normalizeRepo(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GithubCollector.normalizeRepo(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GithubCollector.normalizeRepo("owner"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
