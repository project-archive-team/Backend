package com.projectarchive.backend.collect;

import com.projectarchive.backend.domain.Artifact;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Drive에서 가져온 파일이 어떤 유형으로 들어가는지. 유형이 틀리면 화면 필터와 인용 표시가 어긋난다. */
class DriveClassificationTest {

    @Test
    void codeFilesBecomeCode() {
        assertThat(DriveCollector.classify("train.py")).isEqualTo(Artifact.Type.CODE);
        assertThat(DriveCollector.classify("Main.java")).isEqualTo(Artifact.Type.CODE);
        assertThat(DriveCollector.classify("analysis.ipynb")).isEqualTo(Artifact.Type.CODE);
        assertThat(DriveCollector.classify("docker-compose.yml")).isEqualTo(Artifact.Type.CODE);
    }

    @Test
    void documentsStayDocuments() {
        assertThat(DriveCollector.classify("기획서.pdf")).isEqualTo(Artifact.Type.DOC);
        assertThat(DriveCollector.classify("발표자료.pptx")).isEqualTo(Artifact.Type.DOC);
        assertThat(DriveCollector.classify("README.md")).isEqualTo(Artifact.Type.DOC);
    }

    @Test
    void meetingNotesWinOverExtension() {
        assertThat(DriveCollector.classify("0801 회의록.md")).isEqualTo(Artifact.Type.MEETING);
        assertThat(DriveCollector.classify("weekly-meeting.txt")).isEqualTo(Artifact.Type.MEETING);
    }

    @Test
    void parserAcceptsCodeAndDocumentsAlike() {
        FileParser parser = new FileParser();
        assertThat(parser.supports("train.py")).isTrue();
        assertThat(parser.supports("기획서.pdf")).isTrue();
        assertThat(parser.supports("발표.pptx")).isTrue();
        // 이미지·영상은 텍스트가 없어 색인 대상이 아니다.
        assertThat(parser.supports("screenshot.png")).isFalse();
        assertThat(parser.supports("demo.mp4")).isFalse();
    }
}
