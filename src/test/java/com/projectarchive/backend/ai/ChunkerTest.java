package com.projectarchive.backend.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {

    private final Chunker chunker = new Chunker();

    @Test
    void shortTextStaysWhole() {
        assertThat(chunker.chunk("짧은 문서")).containsExactly("짧은 문서");
    }

    @Test
    void blankTextProducesNothing() {
        assertThat(chunker.chunk(null)).isEmpty();
        assertThat(chunker.chunk("   ")).isEmpty();
    }

    @Test
    void longTextSplitsAndOverlaps() {
        String text = "가".repeat(2500);
        List<String> chunks = chunker.chunk(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        // 겹침 때문에 총량은 원문보다 많아야 한다 — 즉 내용이 잘려 사라지지 않았다.
        assertThat(chunks.stream().mapToInt(String::length).sum()).isGreaterThanOrEqualTo(text.length());
    }

    @Test
    void splitsOnParagraphBoundaryWhenAvailable() {
        String first = "첫 문단입니다. ".repeat(60);   // ~600자
        String second = "둘째 문단입니다. ".repeat(60);
        List<String> chunks = chunker.chunk(first + "\n\n" + second);

        // 문단 경계가 target(1000자) 앞쪽 절반 뒤에 있으므로 거기서 잘려야 한다.
        assertThat(chunks.getFirst()).endsWith("첫 문단입니다.");
    }

    @Test
    void terminatesOnTextWithNoBreaks() {
        // 경계 후보가 전혀 없어도 무한 루프에 빠지지 않아야 한다.
        List<String> chunks = chunker.chunk("x".repeat(5000));
        assertThat(chunks).isNotEmpty();
        assertThat(String.join("", chunks)).contains("x");
    }
}
