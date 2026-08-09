package com.projectarchive.backend.project;

import com.projectarchive.backend.domain.Artifact;
import com.projectarchive.backend.domain.Project;
import com.projectarchive.backend.domain.Source;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public final class ProjectDtos {

    private ProjectDtos() {
    }

    public record CreateRequest(@NotBlank String name,
                                String period,
                                @Min(1) int members,
                                List<String> techStack,
                                String description,
                                String role,
                                String category,
                                Project.State state) {}

    public record AddSourceRequest(@NotNull Source.Type type, String externalRef) {}

    /** 직접 입력한 산출물. 파일 업로드(/files)와 달리 본문을 그대로 받는다. */
    public record CreateArtifactRequest(@NotNull Artifact.Type type,
                                        @NotBlank String title,
                                        @NotBlank String content,
                                        List<String> tags) {}

    /** 대시보드 카드용. 와이어프레임의 프로젝트 목록 항목과 1:1. */
    public record ProjectSummary(Long id,
                                 String name,
                                 Project.Status status,
                                 Project.State state,
                                 String description,
                                 String role,
                                 String category,
                                 long files,
                                 long commits,
                                 Instant createdAt,
                                 Instant lastSyncedAt,
                                 List<Source.Type> sources,
                                 List<String> techStack,
                                 String period,
                                 int members) {}

    public record SourceView(Long id, Source.Type type, String externalRef,
                             Source.Status status, String message, Instant lastSyncedAt) {}

    public record ProjectDetail(ProjectSummary project, List<SourceView> sources) {}

    public record ArtifactView(Long id, Artifact.Type type, String externalId, String title, String path,
                               String content, String author, Instant occurredAt, String url,
                               List<String> tags) {

        public static ArtifactView of(Artifact a) {
            // 영속 컬렉션을 그대로 넘기면 open-in-view=false 환경에서 직렬화 중에 터진다.
            return new ArtifactView(a.getId(), a.getType(), a.getExternalId(), a.getTitle(), a.getPath(),
                    a.getContent(), a.getAuthor(), a.getOccurredAt(), a.getUrl(),
                    List.copyOf(a.getTags()));
        }
    }

    /**
     * 수집 진행상황 폴링용. 소스 단위 상태에 더해 색인 진척도까지 준다 —
     * 소스가 다 끝나도 색인이 남아 있어 화면이 100%를 찍어 놓고 기다리는 일이 없도록.
     */
    public record SyncStatus(Project.Status status, List<SourceView> sources,
                             long artifacts, long indexedArtifacts) {}
}
