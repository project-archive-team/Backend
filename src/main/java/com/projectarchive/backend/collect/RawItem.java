package com.projectarchive.backend.collect;

import com.projectarchive.backend.domain.Artifact;

import java.time.Instant;

/** 소스별 수집기가 뱉는 정규화된 한 건. Artifact로 그대로 upsert된다. */
public record RawItem(
        Artifact.Type type,
        String externalId,
        String title,
        String path,
        String content,
        String author,
        Instant occurredAt,
        String url
) {
}
