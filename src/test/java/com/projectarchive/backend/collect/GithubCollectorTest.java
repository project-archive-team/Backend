package com.projectarchive.backend.collect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubCollectorTest {

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
