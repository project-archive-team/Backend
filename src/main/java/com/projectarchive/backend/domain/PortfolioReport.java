package com.projectarchive.backend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 마지막으로 생성한 포트폴리오 리포트. 프로젝트당 하나만 둔다.
 *
 * 본문은 AI 응답 JSON을 그대로 보관한다 — 항목 구성이 바뀌어도 테이블을 따라 고칠 필요가 없고,
 * 화면은 어차피 같은 구조를 그대로 그린다.
 */
@Entity
@Table(name = "portfolio_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PortfolioReport {

    @Id
    @Column(name = "project_id")
    private Long projectId;

    @Column(nullable = false, columnDefinition = "text")
    private String report;

    @Column(nullable = false)
    private Instant generatedAt = Instant.now();

    public PortfolioReport(Long projectId, String report) {
        this.projectId = projectId;
        this.report = report;
    }

    public void replace(String report) {
        this.report = report;
        this.generatedAt = Instant.now();
    }
}
