package com.projectarchive.backend.collect;

import com.projectarchive.backend.ai.AiClient;
import com.projectarchive.backend.repo.ArtifactRepository;
import com.projectarchive.backend.repo.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 색인 한 묶음을 보내고 그 묶음만 커밋한다.
 *
 * 별도 빈으로 둔 이유: 같은 클래스 안에서 부르면 프록시를 타지 않아 트랜잭션이 나뉘지 않는다.
 * 임베딩은 분당 쿼터가 있어 큰 저장소는 중간에 막히는데, 한 트랜잭션으로 묶으면
 * 그때까지 성공한 것까지 롤백되어 매번 처음부터 다시 하게 된다.
 */
@Component
@RequiredArgsConstructor
public class IndexBatchWriter {

    private final AiClient ai;
    private final ArtifactRepository artifacts;
    private final ProjectRepository projects;

    @Transactional
    public void write(Long projectId, List<AiClient.ChunkPayload> payload, List<Long> artifactIds) {
        if (!payload.isEmpty()) {
            var res = ai.index(new AiClient.IndexRequest(projectId, payload));
            if (res != null && res.techStack() != null) {
                projects.findById(projectId).ifPresent(p -> p.mergeTechStack(res.techStack()));
            }
        }
        // 본문이 빈 아티팩트(예: 메시지 없는 커밋)도 보낼 게 없을 뿐 처리는 끝난 것이다.
        artifacts.findAllById(artifactIds).forEach(a -> a.markIndexed());
    }
}
