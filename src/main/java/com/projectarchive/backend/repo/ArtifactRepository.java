package com.projectarchive.backend.repo;

import com.projectarchive.backend.domain.Artifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ArtifactRepository extends JpaRepository<Artifact, Long> {

    Optional<Artifact> findBySourceIdAndExternalId(Long sourceId, String externalId);

    List<Artifact> findByProjectIdAndType(Long projectId, Artifact.Type type);

    /** 타임라인: 시간 있는 것만, 최신순. */
    List<Artifact> findByProjectIdAndOccurredAtNotNullOrderByOccurredAtDesc(Long projectId);

    List<Artifact> findByProjectIdAndIndexedFalse(Long projectId);

    long countByProjectId(Long projectId);

    /** 아직 벡터로 만들지 않은 자료 수. 0이 아니면 AI가 근거를 못 찾는다. */
    long countByProjectIdAndIndexedFalse(Long projectId);

    long countByProjectIdAndType(Long projectId, Artifact.Type type);
}
