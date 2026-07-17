package com.projectarchive.backend.collect;

import com.projectarchive.backend.ai.AiClient;
import com.projectarchive.backend.ai.Chunker;
import com.projectarchive.backend.domain.Artifact;
import com.projectarchive.backend.domain.OauthToken;
import com.projectarchive.backend.domain.Project;
import com.projectarchive.backend.domain.Source;
import com.projectarchive.backend.repo.ArtifactRepository;
import com.projectarchive.backend.repo.ProjectRepository;
import com.projectarchive.backend.repo.SourceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 소스 한 개 수집 + 인덱싱. 각 메서드가 독립 트랜잭션이라 한 소스가 죽어도 나머지는 커밋된다. */
@Service
@Slf4j
public class SourceSyncer {

    private final Map<Source.Type, Collector> collectors;
    private final SourceRepository sources;
    private final ArtifactRepository artifacts;
    private final ProjectRepository projects;
    private final TokenStore tokenStore;
    private final Chunker chunker;
    private final AiClient ai;

    public SourceSyncer(List<Collector> collectorList, SourceRepository sources, ArtifactRepository artifacts,
                        ProjectRepository projects, TokenStore tokenStore, Chunker chunker, AiClient ai) {
        this.collectors = collectorList.stream().collect(Collectors.toMap(Collector::type, Function.identity()));
        this.sources = sources;
        this.artifacts = artifacts;
        this.projects = projects;
        this.tokenStore = tokenStore;
        this.chunker = chunker;
        this.ai = ai;
    }

    @Transactional
    public void syncSource(Long sourceId) {
        Source source = sources.findById(sourceId).orElseThrow();
        Collector collector = collectors.get(source.getType());
        if (collector == null) {
            // UPLOAD는 수집기가 없다 — 파일이 올라올 때 이미 아티팩트가 만들어져 있다.
            return;
        }
        source.markSyncing();
        try {
            Long ownerId = source.getProject().getOwner().getId();
            String token = tokenStore.accessToken(ownerId, providerOf(source.getType()))
                    .orElseThrow(() -> new IllegalStateException(
                            source.getType() + " 계정이 연결되지 않았습니다"));

            List<RawItem> items = collector.collect(source, token);
            items.forEach(item -> upsert(source, item));
            source.markDone();
            log.info("synced source {} ({}): {} items", sourceId, source.getType(), items.size());
        } catch (Exception e) {
            log.warn("source {} sync failed", sourceId, e);
            source.markFailed(e.getMessage());
        }
    }

    private void upsert(Source source, RawItem item) {
        artifacts.findBySourceIdAndExternalId(source.getId(), item.externalId())
                .ifPresentOrElse(
                        existing -> existing.updateContent(item.title(), item.content(), item.occurredAt()),
                        () -> artifacts.save(new Artifact(source.getProject(), source, item.type(),
                                item.externalId(), item.title(), item.path(), item.content(),
                                item.author(), item.occurredAt(), item.url())));
    }

    /** 아직 인덱싱 안 된 아티팩트를 청킹해서 AI 서버로 넘긴다. */
    @Transactional
    public void indexPending(Long projectId) {
        List<Artifact> pending = artifacts.findByProjectIdAndIndexedFalse(projectId);
        if (pending.isEmpty()) {
            return;
        }
        List<AiClient.ChunkPayload> payload = new ArrayList<>();
        for (Artifact a : pending) {
            List<String> chunks = chunker.chunk(a.getContent());
            for (int i = 0; i < chunks.size(); i++) {
                payload.add(new AiClient.ChunkPayload(a.getId(), a.getType().name(), a.getTitle(),
                        a.getPath(), a.getUrl(), a.getAuthor(), a.getOccurredAt(), i, chunks.get(i)));
            }
        }
        if (payload.isEmpty()) {
            // 본문이 빈 아티팩트(예: 메시지 없는 커밋)는 보낼 게 없으니 인덱싱된 셈 친다.
            pending.forEach(Artifact::markIndexed);
            return;
        }

        var res = ai.index(new AiClient.IndexRequest(projectId, payload));
        pending.forEach(Artifact::markIndexed);

        if (res != null && res.techStack() != null) {
            projects.findById(projectId).ifPresent(p -> p.mergeTechStack(res.techStack()));
        }
        log.info("indexed project {}: {} chunks from {} artifacts", projectId, payload.size(), pending.size());
    }

    @Transactional
    public void markProject(Long projectId, Project.Status status, boolean synced) {
        projects.findById(projectId).ifPresent(p -> {
            p.markStatus(status);
            if (synced) {
                p.markSynced();
            }
        });
    }

    private static OauthToken.Provider providerOf(Source.Type type) {
        return switch (type) {
            case GITHUB -> OauthToken.Provider.GITHUB;
            case GDRIVE -> OauthToken.Provider.GOOGLE;
            case NOTION -> OauthToken.Provider.NOTION;
            case UPLOAD -> throw new IllegalArgumentException("upload has no provider token");
        };
    }
}
