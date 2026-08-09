package com.projectarchive.backend;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 오류 응답에 사유를 담는다.
 *
 * 기본 응답은 {"error":"Bad Request"}뿐이라 화면이 원인을 보여줄 수 없었다.
 * 우리가 던지는 ResponseStatusException의 reason은 전부 사용자에게 보여줄 문구다.
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException e, HttpServletRequest req) {
        String message = e.getReason() == null ? e.getStatusCode().toString() : e.getReason();
        return body(e.getStatusCode(), message, req);
    }

    /** @Valid 실패는 어느 필드가 왜 틀렸는지까지 보여줘야 사용자가 고칠 수 있다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e, HttpServletRequest req) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return body(HttpStatus.BAD_REQUEST, message.isBlank() ? "입력값이 올바르지 않습니다" : message, req);
    }

    /** 예상 못 한 예외의 내부 사정은 흘리지 않는다 — 로그로만 남긴다. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> handleUnexpected(Exception e, HttpServletRequest req) {
        log.error("unhandled exception on {}", req.getRequestURI(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다", req);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatusCode status, String message,
                                                            HttpServletRequest req) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "message", message,
                "path", req.getRequestURI()));
    }
}
