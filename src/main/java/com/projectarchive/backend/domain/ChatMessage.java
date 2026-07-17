package com.projectarchive.backend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** RAG Q&A 대화 로그. 프로젝트당 단일 스레드 — 세션 분리는 필요해지면 그때. */
@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

    public enum Role { USER, ASSISTANT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(columnDefinition = "text", nullable = false)
    private String content;

    /** AI 서버가 돌려준 출처 목록(JSON 문자열). USER 메시지는 null. */
    @Column(columnDefinition = "text")
    private String citations;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public ChatMessage(Project project, Role role, String content, String citations) {
        this.project = project;
        this.role = role;
        this.content = content;
        this.citations = citations;
    }
}
