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
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
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
}
