package com.projectarchive.backend.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * FastAPI AI 서버 호출. 임베딩·벡터검색·에이전트는 전부 저쪽 책임이고,
 * 여기서는 청크를 넘기고 결과를 받아오기만 한다.
 */
@Component
public class AiClient {

    private final RestClient http;

    public AiClient(@Value("${app.ai.base-url}") String baseUrl) {
        // JDK HttpClient는 기본이 HTTP/2라 평문 http에 h2c 업그레이드를 시도한다.
        // uvicorn(h11)은 h2c를 모르고 업그레이드 요청의 본문을 버려서, FastAPI가 body 없음으로 422를 낸다.
        // 업그레이드 실패는 호스트별로 기억되므로 첫 호출만 깨지는 간헐적 502로 보인다 — HTTP/1.1로 고정한다.
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build());
        // LLM 호출이라 느리다. 기본 타임아웃이면 요약·답변 생성이 중간에 끊긴다.
        factory.setReadTimeout(Duration.ofSeconds(120));

        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    // ── 요청/응답 ────────────────────────────────────────────────────────────

    public record ChunkPayload(Long artifactId, String type, String title, String path,
                               String url, String author, Instant occurredAt, int seq, String text) {}

    public record IndexRequest(Long projectId, List<ChunkPayload> chunks) {}

    public record IndexResponse(int indexed, List<String> techStack) {}

    public record ChatRequest(Long projectId, String question) {}

    public record Citation(Long artifactId, String title, String url, String snippet) {}

    public record ChatResponse(String answer, List<Citation> citations) {}

    public record SummaryRequest(Long projectId, Instant since) {}

    public record SummaryResponse(String summary) {}

    public record InterviewRequest(Long projectId, String question) {}

    public record InterviewResponse(String answer, List<Citation> citations) {}

    public record IndexDeleteRequest(Long projectId, List<Long> artifactIds) {}

    // ── 취업 도구 / 포트폴리오 ────────────────────────────────────────────────

    public record CareerStarRequest(Long projectId, String jobRole, String question) {}

    public record StarSections(String situation, String task, String action, String result) {}

    public record CareerStarResponse(String jobRole, String question, StarSections star,
                                     String finalAnswer, List<String> missingEvidence,
                                     List<Citation> citations) {}

    public record InterviewQuestionsRequest(Long projectId, String jobRole, int questionCount) {}

    public record FollowUp(String question, String recommendedAnswer) {}

    public record InterviewQuestion(String category, String likelihood, String question,
                                    String modelAnswer, List<String> checkpoints,
                                    List<FollowUp> followUps, List<Citation> citations) {}

    public record InterviewQuestionsResponse(List<InterviewQuestion> questions) {}

    public record PortfolioReportRequest(Long projectId, String projectName, String period,
                                         String teamSize, String role) {}

    public record ExecutiveSummary(String servicePurpose, String targetUsers, String period,
                                   String teamSize, String role) {}

    public record PortfolioTech(String name, String category, String reason) {}

    public record Contribution(String title, String description, List<String> metrics) {}

    public record Troubleshooting(String title, List<String> tags, String situation,
                                  String action, String result) {}

    public record Retrospective(String technicalGrowth, String collaboration, String futureRoadmap) {}

    public record PortfolioReportResponse(Long projectId, String projectName, Instant generatedAt,
                                          String oneLineSummary, ExecutiveSummary executiveSummary,
                                          List<PortfolioTech> techStack, String systemArchitecture,
                                          String dataPipeline, List<Contribution> contributions,
                                          List<Troubleshooting> troubleshooting,
                                          Retrospective retrospective, List<String> missingEvidence,
                                          List<Citation> citations) {}

    // ── 호출 ────────────────────────────────────────────────────────────────

    public IndexResponse index(IndexRequest req) {
        return http.post().uri("/index").body(req).retrieve().body(IndexResponse.class);
    }

    public ChatResponse chat(ChatRequest req) {
        return http.post().uri("/chat").body(req).retrieve().body(ChatResponse.class);
    }

    public SummaryResponse summary(SummaryRequest req) {
        return http.post().uri("/summary").body(req).retrieve().body(SummaryResponse.class);
    }

    public InterviewResponse interview(InterviewRequest req) {
        return http.post().uri("/interview").body(req).retrieve().body(InterviewResponse.class);
    }

    public CareerStarResponse careerStar(CareerStarRequest req) {
        return http.post().uri("/career/star").body(req).retrieve().body(CareerStarResponse.class);
    }

    public InterviewQuestionsResponse interviewQuestions(InterviewQuestionsRequest req) {
        return http.post().uri("/career/interview-questions").body(req)
                .retrieve().body(InterviewQuestionsResponse.class);
    }

    public PortfolioReportResponse portfolioReport(PortfolioReportRequest req) {
        return http.post().uri("/portfolio/report").body(req)
                .retrieve().body(PortfolioReportResponse.class);
    }

    public void indexDelete(IndexDeleteRequest req) {
        http.post().uri("/index/delete").body(req).retrieve().toBodilessEntity();
    }

    public void deleteProjectIndex(Long projectId) {
        http.delete().uri("/index/projects/{id}", projectId).retrieve().toBodilessEntity();
    }
}
