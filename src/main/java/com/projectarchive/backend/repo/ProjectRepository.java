package com.projectarchive.backend.repo;

import com.projectarchive.backend.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    /** 소유자 검증까지 한 번에 — 남의 프로젝트는 애초에 안 나온다. */
    Optional<Project> findByIdAndOwnerId(Long id, Long ownerId);
}
