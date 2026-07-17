package com.projectarchive.backend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 프로젝트에 연결된 수집 소스 하나.
 * 기획서: "자료가 일부만 있어도 동작" — 소스는 0..N개이고 각각 독립적으로 실패할 수 있다.
 */
@Entity
@Table(name = "sources", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "type", "external_ref"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Source {

    public enum Type { GITHUB, GDRIVE, NOTION, UPLOAD }

    public enum Status { PENDING, SYNCING, DONE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    /** GITHUB=owner/repo, GDRIVE=folderId, NOTION=pageId, UPLOAD=null */
    @Column(name = "external_ref")
    private String externalRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    /** 실패 사유. 성공 시 null로 되돌린다. */
    @Column(length = 1000)
    private String message;

    private Instant lastSyncedAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Source(Project project, Type type, String externalRef) {
        this.project = project;
        this.type = type;
        this.externalRef = externalRef;
    }

    public void markSyncing() {
        this.status = Status.SYNCING;
        this.message = null;
    }

    public void markDone() {
        this.status = Status.DONE;
        this.message = null;
        this.lastSyncedAt = Instant.now();
    }

    public void markFailed(String message) {
        this.status = Status.FAILED;
        // 원문 예외 메시지가 컬럼보다 길 수 있다.
        this.message = message == null ? "unknown error"
                : message.substring(0, Math.min(message.length(), 1000));
    }
}
