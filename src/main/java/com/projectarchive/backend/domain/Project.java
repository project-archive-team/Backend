package com.projectarchive.backend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "projects")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project {

    public enum Status { PENDING, ANALYZING, DONE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User owner;

    @Column(nullable = false)
    private String name;

    /** 자유 입력. 예: "8주" */
    private String period;

    private int memberCount = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "project_tech_stack", joinColumns = @JoinColumn(name = "project_id"))
    @Column(name = "tech")
    private List<String> techStack = new ArrayList<>();

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant lastSyncedAt;

    public Project(User owner, String name, String period, int memberCount, List<String> techStack) {
        this.owner = owner;
        this.name = name;
        this.period = period;
        this.memberCount = memberCount;
        this.techStack = techStack == null ? new ArrayList<>() : new ArrayList<>(techStack);
    }

    public void markStatus(Status status) {
        this.status = status;
    }

    public void markSynced() {
        this.lastSyncedAt = Instant.now();
    }

    /** AI가 코드/문서에서 추출한 스택으로 갱신. 사용자가 직접 넣은 값과 합집합. */
    public void mergeTechStack(List<String> detected) {
        detected.stream().filter(t -> !techStack.contains(t)).forEach(techStack::add);
    }
}
