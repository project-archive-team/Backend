package com.projectarchive.backend.repo;

import com.projectarchive.backend.domain.Source;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SourceRepository extends JpaRepository<Source, Long> {
    List<Source> findByProjectId(Long projectId);

    Optional<Source> findByProjectIdAndTypeAndExternalRef(Long projectId, Source.Type type, String externalRef);
}
