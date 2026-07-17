package com.projectarchive.backend.project;

import com.projectarchive.backend.auth.CurrentUserId;
import com.projectarchive.backend.domain.Artifact;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static com.projectarchive.backend.project.ProjectDtos.*;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService service;

    @GetMapping
    public List<ProjectSummary> list(@CurrentUserId Long userId) {
        return service.list(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectSummary create(@CurrentUserId Long userId, @Valid @RequestBody CreateRequest req) {
        return service.create(userId, req);
    }

    @GetMapping("/{id}")
    public ProjectDetail detail(@CurrentUserId Long userId, @PathVariable Long id) {
        return service.detail(id, userId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId Long userId, @PathVariable Long id) {
        service.delete(id, userId);
    }

    @PostMapping("/{id}/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public SourceView addSource(@CurrentUserId Long userId, @PathVariable Long id,
                                @Valid @RequestBody AddSourceRequest req) {
        return service.addSource(id, userId, req);
    }

    @DeleteMapping("/{id}/sources/{sourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeSource(@CurrentUserId Long userId, @PathVariable Long id, @PathVariable Long sourceId) {
        service.removeSource(id, userId, sourceId);
    }

    @PostMapping("/{id}/files")
    public List<ArtifactView> upload(@CurrentUserId Long userId, @PathVariable Long id,
                                     @RequestParam("files") List<MultipartFile> files) {
        return service.upload(id, userId, files);
    }

    @PostMapping("/{id}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SyncStatus sync(@CurrentUserId Long userId, @PathVariable Long id) {
        return service.startSync(id, userId);
    }

    /** 수집 진행상황 폴링. */
    @GetMapping("/{id}/sync")
    public SyncStatus syncStatus(@CurrentUserId Long userId, @PathVariable Long id) {
        return service.syncStatus(id, userId);
    }

    @GetMapping("/{id}/artifacts")
    public List<ArtifactView> artifacts(@CurrentUserId Long userId, @PathVariable Long id,
                                        @RequestParam(required = false) Artifact.Type type) {
        return service.artifacts(id, userId, type);
    }

    @GetMapping("/{id}/timeline")
    public List<ArtifactView> timeline(@CurrentUserId Long userId, @PathVariable Long id) {
        return service.timeline(id, userId);
    }
}
