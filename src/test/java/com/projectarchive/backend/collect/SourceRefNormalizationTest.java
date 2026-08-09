package com.projectarchive.backend.collect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 사용자는 주소창의 URL을 그대로 붙여넣는다. 그걸 식별자로 쓰면 수집이 "찾을 수 없음"으로 죽는다.
 * 실제로 Drive가 그렇게 실패했다.
 */
class SourceRefNormalizationTest {

    @Test
    void drivePullsTheFolderIdOutOfPastedUrls() {
        assertThat(DriveCollector.normalizeFolderId(
                "https://drive.google.com/drive/folders/1MKI7glt3GeTWecPuslY3"))
                .isEqualTo("1MKI7glt3GeTWecPuslY3");
        assertThat(DriveCollector.normalizeFolderId(
                "https://drive.google.com/drive/u/0/folders/1AbC-dEf_9?usp=sharing"))
                .isEqualTo("1AbC-dEf_9");
        assertThat(DriveCollector.normalizeFolderId(
                "https://drive.google.com/open?id=1XyZ_abc"))
                .isEqualTo("1XyZ_abc");
        // 이미 ID면 그대로 둔다.
        assertThat(DriveCollector.normalizeFolderId("1MKI7glt3GeTWecPuslY3"))
                .isEqualTo("1MKI7glt3GeTWecPuslY3");
    }

    @Test
    void driveRejectsAddressesWithoutAFolderId() {
        assertThatThrownBy(() -> DriveCollector.normalizeFolderId("https://drive.google.com/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DriveCollector.normalizeFolderId("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void notionPullsThePageIdOutOfPastedUrls() {
        // 제목이 앞에 붙고 끝에 32자리 id가 오는 흔한 형태.
        assertThat(NotionCollector.normalizePageId(
                "https://www.notion.so/team/Sprint-Notes-1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d?pvs=4"))
                .isEqualTo("1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d");
        // 대시가 들어간 UUID는 Notion이 그대로 받는다.
        assertThat(NotionCollector.normalizePageId(
                "https://www.notion.so/1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d"))
                .isEqualTo("1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d");
        assertThat(NotionCollector.normalizePageId("1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d"))
                .isEqualTo("1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d");
    }

    @Test
    void notionTreatsEmptyRefAsEveryPageSharedWithTheIntegration() {
        assertThat(NotionCollector.normalizePageId(null)).isNull();
        assertThat(NotionCollector.normalizePageId("  ")).isNull();
    }
}
