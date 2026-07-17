package com.projectarchive.backend.ai;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 문서를 임베딩 단위로 자른다.
 * ponytail: 문단 경계를 존중하는 고정 크기 슬라이딩 윈도우. 토크나이저 기반 분할이나
 * 시맨틱 청킹은 검색 품질이 실제로 부족하다고 확인되면 그때.
 */
@Component
public class Chunker {

    private static final int TARGET = 1000;
    private static final int OVERLAP = 150;

    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.strip();
        if (normalized.length() <= TARGET) {
            return List.of(normalized);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + TARGET, normalized.length());
            if (end < normalized.length()) {
                end = boundary(normalized, start, end);
            }
            String piece = normalized.substring(start, end).strip();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end >= normalized.length()) {
                break;
            }
            // 겹치게 물려서 경계에 걸친 문장이 통째로 사라지는 걸 막는다.
            start = Math.max(end - OVERLAP, start + 1);
        }
        return chunks;
    }

    /** target 근처에서 문단 → 줄 → 공백 순으로 자를 곳을 찾는다. 없으면 그냥 자른다. */
    private int boundary(String s, int start, int end) {
        int floor = start + TARGET / 2;
        for (String sep : new String[]{"\n\n", "\n", " "}) {
            int idx = s.lastIndexOf(sep, end);
            if (idx > floor) {
                return idx + sep.length();
            }
        }
        return end;
    }
}
