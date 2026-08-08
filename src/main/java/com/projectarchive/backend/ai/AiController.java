package com.projectarchive.backend.ai;

import com.projectarchive.backend.auth.CurrentUserId;
import com.projectarchive.backend.domain.ChatMessage;
import com.projectarchive.backend.project.ProjectService;
import com.projectarchive.backend.repo.ChatMessageRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** RAG Q&A · 요약 · 면접 답변. 전부 FastAPI가 실제 일을 하고 여기선 권한 확인과 로그 저장만 한다. */
@RestController
@RequestMapping("/api/projects/{id}")
@RequiredArgsConstructor
@Slf4j
public class AiController {

    private final AiClient ai;
    private final ProjectService projectService;
    private final ChatMessageRepository chatMessages;
    private final ObjectMapper objectMapper;

    public record AskRequest(@NotBlank String question) {}

    public record ChatView(Long id, ChatMessage.Role role, String content,
                           List<AiClient.Citation> citations, Instant createdAt) {}

    @GetMapping("/chat")
    public List<ChatView> history(@CurrentUserId Long userId, @PathVariable Long id) {
        projectService.owned(id, userId);
        return chatMessages.findByProjectIdOrderByCreatedAtAsc(id).stream().map(this::toView).toList();
    }

    @PostMapping("/chat")
    public ChatView ask(@CurrentUserId Long userId, @PathVariable Long id, @Valid @RequestBody AskRequest req) {
        var project = projectService.owned(id, userId);
        chatMessages.save(new ChatMessage(project, ChatMessage.Role.USER, req.question(), null));

        var res = call(() -> ai.chat(new AiClient.ChatRequest(id, req.question())));
        String citations = writeJson(res.citations());
        var saved = chatMessages.save(new ChatMessage(project, ChatMessage.Role.ASSISTANT, res.answer(), citations));
        return toView(saved);
    }

    /** 기본 7일 — 와이어프레임의 "이번 주 누가 뭐 했는지". */
    @GetMapping("/summary")
    public AiClient.SummaryResponse summary(@CurrentUserId Long userId, @PathVariable Long id,
                                            @RequestParam(defaultValue = "7") int days) {
        projectService.owned(id, userId);
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        return call(() -> ai.summary(new AiClient.SummaryRequest(id, since)));
    }

    @PostMapping("/interview")
    public AiClient.InterviewResponse interview(@CurrentUserId Long userId, @PathVariable Long id,
                                                @Valid @RequestBody AskRequest req) {
        projectService.owned(id, userId);
        return call(() -> ai.interview(new AiClient.InterviewRequest(id, req.question())));
    }

    public record StarRequest(@NotBlank String jobRole, @NotBlank String question) {}

    /** 자소서 문항에 대한 STAR 4단 분해. 화면의 STAR 박스가 이 응답을 그대로 쓴다. */
    @PostMapping("/career/star")
    public AiClient.CareerStarResponse careerStar(@CurrentUserId Long userId, @PathVariable Long id,
                                                  @Valid @RequestBody StarRequest req) {
        projectService.owned(id, userId);
        return call(() -> ai.careerStar(new AiClient.CareerStarRequest(id, req.jobRole(), req.question())));
    }

    public record InterviewQuestionsRequest(@NotBlank String jobRole, Integer questionCount) {}

    @PostMapping("/career/interview-questions")
    public AiClient.InterviewQuestionsResponse interviewQuestions(@CurrentUserId Long userId,
                                                                  @PathVariable Long id,
                                                                  @Valid @RequestBody InterviewQuestionsRequest req) {
        projectService.owned(id, userId);
        // AI 서버가 1~5만 받는다. 화면이 값을 안 주면 3장.
        int count = req.questionCount() == null ? 3 : Math.clamp(req.questionCount(), 1, 5);
        return call(() -> ai.interviewQuestions(new AiClient.InterviewQuestionsRequest(id, req.jobRole(), count)));
    }

    /** 수집된 자료로 포트폴리오 전체를 구조화한다. */
    @PostMapping("/portfolio")
    public AiClient.PortfolioReportResponse portfolio(@CurrentUserId Long userId, @PathVariable Long id) {
        var project = projectService.owned(id, userId);
        // AI 서버가 이 필드들을 "있으면 1자 이상"으로 검증한다. 빈 문자열은 null로 눕혀야 422를 안 맞는다.
        return call(() -> ai.portfolioReport(new AiClient.PortfolioReportRequest(
                id, blankToNull(project.getName()), blankToNull(project.getPeriod()),
                project.getMemberCount() + "인", blankToNull(project.getRole()))));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** AI 서버가 죽었을 때 500 대신 502로 — 프론트가 우리 버그와 구분할 수 있게. */
    private <T> T call(java.util.function.Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.error("AI server call failed", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 서버 호출 실패");
        }
    }

    private ChatView toView(ChatMessage m) {
        return new ChatView(m.getId(), m.getRole(), m.getContent(), readCitations(m.getCitations()), m.getCreatedAt());
    }

    private String writeJson(List<AiClient.Citation> citations) {
        return citations == null ? null : objectMapper.writeValueAsString(citations);
    }

    private List<AiClient.Citation> readCitations(String json) {
        if (json == null) {
            return List.of();
        }
        return objectMapper.readValue(json, new tools.jackson.core.type.TypeReference<List<AiClient.Citation>>() {});
    }
}
