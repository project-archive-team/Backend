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
    void tellsOrganizationAddressFromRepositoryAddress() {
        // 조직/계정만 가리키는 주소 — 저장소별로 펼쳐야 한다.
        assertThat(GithubCollector.isOwnerOnly("https://github.com/project-archive-team")).isTrue();
        assertThat(GithubCollector.isOwnerOnly("https://github.com/project-archive-team/")).isTrue();
        assertThat(GithubCollector.isOwnerOnly("project-archive-team")).isTrue();
        assertThat(GithubCollector.ownerOf("https://github.com/project-archive-team")).isEqualTo("project-archive-team");

        // 저장소까지 있으면 그대로 하나만 등록한다.
        assertThat(GithubCollector.isOwnerOnly("https://github.com/owner/repo")).isFalse();
        assertThat(GithubCollector.isOwnerOnly("owner/repo")).isFalse();
        assertThat(GithubCollector.isOwnerOnly("https://github.com/owner/repo/tree/main")).isFalse();
    }

    @Test
    void organizationExpansionSkipsForksAndArchivedAndRespectsTheLimit() {
        var repos = JSON.readTree("""
                [
                  {"full_name": "org/Backend",   "fork": false, "archived": false},
                  {"full_name": "org/forked",    "fork": true,  "archived": false},
                  {"full_name": "org/retired",   "fork": false, "archived": true},
                  {"full_name": "org/Frontend",  "fork": false, "archived": false}
                ]
                """);

        assertThat(GithubCollector.pickRepos(repos))
                .containsExactly("org/Backend", "org/Frontend");
    }

    @Test
    void organizationExpansionStopsAtTheLimit() {
        var sb = new StringBuilder("[");
        for (int i = 0; i < GithubCollector.ORG_REPO_LIMIT + 5; i++) {
            sb.append(i > 0 ? "," : "")
              .append("{\"full_name\":\"org/r").append(i).append("\",\"fork\":false,\"archived\":false}");
        }
        var repos = JSON.readTree(sb.append("]").toString());

        assertThat(GithubCollector.pickRepos(repos)).hasSize(GithubCollector.ORG_REPO_LIMIT);
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
