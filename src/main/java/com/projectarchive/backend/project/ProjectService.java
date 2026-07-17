package com.projectarchive.backend.project;

import com.projectarchive.backend.collect.FileParser;
import com.projectarchive.backend.collect.SyncService;
import com.projectarchive.backend.domain.*;
import com.projectarchive.backend.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static com.projectarchive.backend.project.ProjectDtos.*;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projects;
    private final SourceRepository sources;
    private final ArtifactRepository artifacts;
    private final UserRepository users;
    private final FileParser fileParser;
    private final SyncService syncService;

    /** 모든 진입점이 이걸 거친다 — 남의 프로젝트는 404. */
    @Transactional(readOnly = true)
    public Project owned(Long projectId, Long userId) {
        return projects.findByIdAndOwnerId(projectId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found"));
    }

    @Transactional(readOnly = true)
    public List<ProjectSummary> list(Long userId) {
        return projects.findByOwnerIdOrderByCreatedAtDesc(userId).stream().map(this::summarize).toList();
    }

    @Transactional
    public ProjectSummary create(Long userId, CreateRequest req) {
        User owner = users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        Project project = projects.save(new Project(owner, req.name(), req.period(),
                Math.max(req.members(), 1), req.techStack()));
        return summarize(project);
    }

    @Transactional(readOnly = true)
    public ProjectDetail detail(Long projectId, Long userId) {
        Project project = owned(projectId, userId);
        return new ProjectDetail(summarize(project), sourceViews(projectId));
    }

    @Transactional
    public void delete(Long projectId, Long userId) {
        projects.delete(owned(projectId, userId));
    }

    @Transactional
    public SourceView addSource(Long projectId, Long userId, AddSourceRequest req) {
        Project project = owned(projectId, userId);
        if (req.type() == Source.Type.GITHUB && (req.externalRef() == null || req.externalRef().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitHub 소스는 저장소 URL이 필요합니다");
        }
        Source source = sources.findByProjectIdAndTypeAndExternalRef(projectId, req.type(), req.externalRef())
                .orElseGet(() -> sources.save(new Source(project, req.type(), req.externalRef())));
        return toView(source);
    }

    @Transactional
    public void removeSource(Long projectId, Long userId, Long sourceId) {
        owned(projectId, userId);
        Source source = sources.findById(sourceId)
                .filter(s -> s.getProject().getId().equals(projectId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "source not found"));
        sources.delete(source);
    }

    /**
     * 업로드 파일은 즉시 파싱해 아티팩트로 만든다 — 원본 바이너리는 보관하지 않는다.
     * ponytail: RAG에 필요한 건 텍스트뿐이라 S3 없이 시작한다. 원본 다운로드 기능이 생기면 그때 붙인다.
     */
    @Transactional
    public List<ArtifactView> upload(Long projectId, Long userId, List<MultipartFile> files) {
        Project project = owned(projectId, userId);
        Source source = sources.findByProjectIdAndTypeAndExternalRef(projectId, Source.Type.UPLOAD, null)
                .orElseGet(() -> sources.save(new Source(project, Source.Type.UPLOAD, null)));

        List<ArtifactView> saved = files.stream().map(file -> {
            String name = file.getOriginalFilename();
            if (!fileParser.supports(name)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "지원하지 않는 파일 형식: " + name);
            }
            String text;
            try {
                text = fileParser.parse(file);
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " 파싱 실패: " + e.getMessage());
            }
            Artifact artifact = artifacts.findBySourceIdAndExternalId(source.getId(), name)
                    .map(existing -> {
                        existing.updateContent(name, text, Instant.now());
                        return existing;
                    })
                    .orElseGet(() -> artifacts.save(new Artifact(project, source,
                            isMeetingFile(name) ? Artifact.Type.MEETING : Artifact.Type.DOC,
                            name, name, name, text, null, Instant.now(), null)));
            return ArtifactView.of(artifact);
        }).toList();

        source.markDone();
        return saved;
    }

    /** 동기화는 비동기로 던지고 즉시 리턴한다. 진행상황은 syncStatus로 폴링. */
    @Transactional
    public SyncStatus startSync(Long projectId, Long userId) {
        Project project = owned(projectId, userId);
        if (sources.findByProjectId(projectId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "연결된 소스가 없습니다");
        }
        project.markStatus(Project.Status.ANALYZING);
        syncService.syncProject(projectId);
        return new SyncStatus(Project.Status.ANALYZING, sourceViews(projectId));
    }

    @Transactional(readOnly = true)
    public SyncStatus syncStatus(Long projectId, Long userId) {
        Project project = owned(projectId, userId);
        return new SyncStatus(project.getStatus(), sourceViews(projectId));
    }

    @Transactional(readOnly = true)
    public List<ArtifactView> artifacts(Long projectId, Long userId, Artifact.Type type) {
        owned(projectId, userId);
        List<Artifact> found = type == null
                ? artifacts.findByProjectIdAndOccurredAtNotNullOrderByOccurredAtDesc(projectId)
                : artifacts.findByProjectIdAndType(projectId, type);
        return found.stream().map(ArtifactView::of).toList();
    }

    /** 타임라인 = 시각이 있는 아티팩트를 최신순으로. 별도 테이블을 두지 않는다. */
    @Transactional(readOnly = true)
    public List<ArtifactView> timeline(Long projectId, Long userId) {
        owned(projectId, userId);
        return artifacts.findByProjectIdAndOccurredAtNotNullOrderByOccurredAtDesc(projectId)
                .stream().map(ArtifactView::of).toList();
    }

    // ── 내부 ────────────────────────────────────────────────────────────────

    private ProjectSummary summarize(Project p) {
        Long id = p.getId();
        long commits = artifacts.countByProjectIdAndType(id, Artifact.Type.COMMIT);
        return new ProjectSummary(id, p.getName(), p.getStatus(),
                artifacts.countByProjectId(id) - commits,
                commits,
                p.getLastSyncedAt(),
                sources.findByProjectId(id).stream().map(Source::getType).distinct().toList(),
                // 영속 컬렉션을 그대로 넘기면 트랜잭션 종료 후 Jackson이 초기화를 시도하다 터진다
                // (open-in-view=false). 세션 안에서 복사해서 내보낸다.
                List.copyOf(p.getTechStack()),
                p.getPeriod(),
                p.getMemberCount());
    }

    private List<SourceView> sourceViews(Long projectId) {
        return sources.findByProjectId(projectId).stream().map(ProjectService::toView).toList();
    }

    private static SourceView toView(Source s) {
        return new SourceView(s.getId(), s.getType(), s.getExternalRef(),
                s.getStatus(), s.getMessage(), s.getLastSyncedAt());
    }

    private static boolean isMeetingFile(String name) {
        String n = name.toLowerCase();
        return n.contains("회의") || n.contains("meeting") || n.contains("minutes");
    }
}
