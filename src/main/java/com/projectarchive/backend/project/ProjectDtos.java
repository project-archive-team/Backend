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
                                List<String> techStack) {}

    public record AddSourceRequest(@NotNull Source.Type type, String externalRef) {}

    /** 대시보드 카드용. 와이어프레임의 프로젝트 목록 항목과 1:1. */
    public record ProjectSummary(Long id,
                                 String name,
                                 Project.Status status,
                                 long files,
                                 long commits,
                                 Instant lastSyncedAt,
                                 List<Source.Type> sources,
                                 List<String> techStack,
                                 String period,
                                 int members) {}

    public record SourceView(Long id, Source.Type type, String externalRef,
                             Source.Status status, String message, Instant lastSyncedAt) {}

    public record ProjectDetail(ProjectSummary project, List<SourceView> sources) {}

    public record ArtifactView(Long id, Artifact.Type type, String title, String path,
                               String author, Instant occurredAt, String url) {

        public static ArtifactView of(Artifact a) {
            return new ArtifactView(a.getId(), a.getType(), a.getTitle(), a.getPath(),
                    a.getAuthor(), a.getOccurredAt(), a.getUrl());
        }
    }

    /** 수집 진행상황 폴링용. 소스 단위 상태를 그대로 노출한다. */
    public record SyncStatus(Project.Status status, List<SourceView> sources) {}
}
