package com.projectarchive.backend.project;

import com.projectarchive.backend.ai.AiClient;
import com.projectarchive.backend.collect.FileParser;
import com.projectarchive.backend.collect.DriveCollector;
import com.projectarchive.backend.collect.GithubCollector;
import com.projectarchive.backend.collect.NotionCollector;
import com.projectarchive.backend.collect.TokenStore;
import com.projectarchive.backend.collect.SyncService;
import com.projectarchive.backend.domain.*;
import com.projectarchive.backend.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class ProjectService {

    private final ProjectRepository projects;
    private final SourceRepository sources;
    private final ArtifactRepository artifacts;
    private final UserRepository users;
    private final FileParser fileParser;
    private final SyncService syncService;
    private final AiClient ai;
    private final TokenStore tokenStore;
    private final GithubCollector githubCollector;

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
        project.describe(req.description(), req.role(), req.category(), req.state());
        return summarize(project);
    }

    @Transactional(readOnly = true)
    public ProjectDetail detail(Long projectId, Long userId) {
        Project project = owned(projectId, userId);
        return new ProjectDetail(summarize(project), sourceViews(projectId));
    }

    @Transactional
    public void delete(Long projectId, Long userId) {
        Project project = owned(projectId, userId);
        dropFromIndex(() -> ai.deleteProjectIndex(projectId));
        projects.delete(project);
    }

    /**
     * 수집 대상 등록.
     *
     * GitHub은 조직/계정 주소를 받으면 그 아래 저장소를 각각의 소스로 펼친다 —
     * 보통 저장소 단위로 스택이 갈리므로 상태·재수집·삭제도 저장소 단위로 다뤄야 한다.
     */
    @Transactional
    public List<SourceView> addSource(Long projectId, Long userId, AddSourceRequest req) {
        Project project = owned(projectId, userId);
        if (req.type() == Source.Type.GITHUB && (req.externalRef() == null || req.externalRef().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitHub 소스는 저장소 또는 조직 주소가 필요합니다");
        }

        if (req.type() == Source.Type.GITHUB && GithubCollector.isOwnerOnly(req.externalRef())) {
            return expandOwner(project, userId, GithubCollector.ownerOf(req.externalRef()));
        }

        // 사용자는 브라우저 주소를 그대로 붙여넣는다. 등록 시점에 식별자로 바꿔 둬야
        // 같은 대상이 중복 등록되지 않고, 수집 때 "찾을 수 없음"으로 실패하지도 않는다.
        String ref = switch (req.type()) {
            case GITHUB -> GithubCollector.normalizeRepo(req.externalRef());
            case GDRIVE -> DriveCollector.normalizeFolderId(req.externalRef());
            case NOTION -> NotionCollector.normalizePageId(req.externalRef());
            case UPLOAD -> req.externalRef();
        };

        Source source = sources.findByProjectIdAndTypeAndExternalRef(projectId, req.type(), ref)
                .orElseGet(() -> sources.save(new Source(project, req.type(), ref)));
        return List.of(toView(source));
    }

    private List<SourceView> expandOwner(Project project, Long userId, String owner) {
        String token = tokenStore.accessToken(userId, OauthToken.Provider.GITHUB)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "조직 전체를 등록하려면 GitHub 로그인이 먼저 필요합니다"));

        List<String> repos;
        try {
            repos = githubCollector.listRepos(owner, token);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    owner + " 의 저장소 목록을 가져오지 못했습니다: " + e.getMessage());
        }
        if (repos.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    owner + " 아래에 수집할 저장소가 없습니다 (포크·아카이브 제외)");
        }

        return repos.stream()
                .map(repo -> sources
                        .findByProjectIdAndTypeAndExternalRef(project.getId(), Source.Type.GITHUB, repo)
                        .orElseGet(() -> sources.save(new Source(project, Source.Type.GITHUB, repo))))
                .map(ProjectService::toView)
                .toList();
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

    /**
     * 화면에서 제목·유형·본문을 직접 입력해 등록하는 경로. 업로드 파일과 같은 UPLOAD 소스에 붙는다.
     * externalId는 제목으로 잡아 같은 제목을 다시 올리면 덮어쓴다.
     */
    @Transactional
    public ArtifactView addArtifact(Long projectId, Long userId, CreateArtifactRequest req) {
        Project project = owned(projectId, userId);
        Source source = sources.findByProjectIdAndTypeAndExternalRef(projectId, Source.Type.UPLOAD, null)
                .orElseGet(() -> sources.save(new Source(project, Source.Type.UPLOAD, null)));

        String author = users.findById(userId).map(User::getName).orElse(null);
        Artifact artifact = artifacts.findBySourceIdAndExternalId(source.getId(), req.title())
                .map(existing -> {
                    existing.updateContent(req.title(), req.content(), Instant.now());
                    return existing;
                })
                .orElseGet(() -> artifacts.save(new Artifact(project, source, req.type(),
                        req.title(), req.title(), null, req.content(), author, Instant.now(), null)));
        artifact.replaceTags(req.tags());
        source.markDone();
        return ArtifactView.of(artifact);
    }

    /** DB에서 지우기 전에 AI 벡터 인덱스에서도 뺀다 — 안 그러면 삭제한 자료가 답변에 계속 인용된다. */
    @Transactional
    public void deleteArtifact(Long projectId, Long userId, Long artifactId) {
        owned(projectId, userId);
        Artifact artifact = artifacts.findById(artifactId)
                .filter(a -> a.getProject().getId().equals(projectId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "artifact not found"));
        dropFromIndex(() -> ai.indexDelete(new AiClient.IndexDeleteRequest(projectId, List.of(artifactId))));
        artifacts.delete(artifact);
    }

    /** 인덱스 정리는 실패해도 삭제 자체를 막지 않는다 — 남은 벡터는 다음 재색인에서 정리된다. */
    private void dropFromIndex(Runnable call) {
        try {
            call.run();
        } catch (Exception e) {
            log.warn("AI 인덱스 삭제 실패 — 본 삭제는 계속 진행한다", e);
        }
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
        return status(project);
    }

    @Transactional(readOnly = true)
    public SyncStatus syncStatus(Long projectId, Long userId) {
        Project project = owned(projectId, userId);
        return status(project);
    }

    /**
     * AI 기능을 쓰기 전에 색인이 끝나 있는지 확인한다.
     *
     * 색인은 동기화에서만 돌기 때문에, 수집만 되고 색인이 실패해 있으면 AI는 근거를 못 찾고
     * "자료에서 확인되지 않았다"만 답한다. 그럴 땐 색인을 걸어 두고 기다리라고 알려준다.
     */
    @Transactional
    public void requireIndexed(Long projectId) {
        if (artifacts.countByProjectId(projectId) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "수집된 자료가 없습니다. 소스를 등록하고 먼저 동기화해 주세요.");
        }
        long pending = artifacts.countByProjectIdAndIndexedFalse(projectId);
        if (pending > 0) {
            syncService.indexProject(projectId);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "자료 " + pending + "건이 아직 AI 색인 전입니다. 색인을 시작했습니다.");
        }
    }

    private SyncStatus status(Project project) {
        Long id = project.getId();
        return new SyncStatus(project.getStatus(), sourceViews(id),
                artifacts.countByProjectId(id),
                artifacts.countByProjectId(id) - artifacts.countByProjectIdAndIndexedFalse(id));
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
        return new ProjectSummary(id, p.getName(), p.getStatus(), p.getState(),
                p.getDescription(), p.getRole(), p.getCategory(),
                artifacts.countByProjectId(id) - commits,
                commits,
                p.getCreatedAt(),
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
