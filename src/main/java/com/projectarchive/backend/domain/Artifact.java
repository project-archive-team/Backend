package com.projectarchive.backend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 모든 소스가 정규화되어 떨어지는 공통 단위.
 * 커밋, 코드 파일, 문서, 회의록이 전부 이 하나로 표현된다 — 타임라인은 occurredAt 정렬로 파생하고,
 * RAG 인덱싱은 content를 청킹해서 AI 서버로 보낸다.
 */
@Entity
@Table(name = "artifacts", uniqueConstraints = @UniqueConstraint(columnNames = {"source_id", "external_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Artifact {

    public enum Type { COMMIT, CODE, DOC, MEETING }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Source source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    /** 소스 내 고유 키. 커밋 sha, drive fileId, notion pageId, 업로드 파일명. 재동기화 시 중복 방지용. */
    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(nullable = false)
    private String title;

    /** 저장소 내 경로 또는 문서 경로. 없으면 null. */
    private String path;

    @Column(columnDefinition = "text")
    private String content;

    private String author;

    /** 커밋 시각 / 문서 수정 시각. 타임라인 정렬 키. */
    private Instant occurredAt;

    /** 원본으로 돌아가는 링크. 답변 출처 표시에 쓴다. */
    @Column(length = 2000)
    private String url;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    /** AI 서버 인덱싱 완료 여부. 재동기화 시 바뀐 것만 다시 보내기 위한 플래그. */
    @Column(nullable = false)
    private boolean indexed = false;

    public Artifact(Project project, Source source, Type type, String externalId,
                    String title, String path, String content, String author,
                    Instant occurredAt, String url) {
        this.project = project;
        this.source = source;
        this.type = type;
        this.externalId = externalId;
        this.title = title;
        this.path = path;
        this.content = content;
        this.author = author;
        this.occurredAt = occurredAt;
        this.url = url;
    }

    /** 재동기화로 내용이 바뀌면 인덱스를 무효화한다. */
    public void updateContent(String title, String content, Instant occurredAt) {
        boolean changed = !java.util.Objects.equals(this.content, content);
        this.title = title;
        this.content = content;
        this.occurredAt = occurredAt;
        if (changed) {
            this.indexed = false;
        }
    }

    public void markIndexed() {
        this.indexed = true;
    }
}
