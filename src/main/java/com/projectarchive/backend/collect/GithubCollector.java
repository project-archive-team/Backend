package com.projectarchive.backend.collect;

import tools.jackson.databind.JsonNode;
import com.projectarchive.backend.domain.Artifact;
import com.projectarchive.backend.domain.Source;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

@Component
@Slf4j
public class GithubCollector implements Collector {

    /** 커밋 수집 상한. 학부 프로젝트 규모에서 1페이지면 대개 충분하다. */
    private static final int COMMIT_LIMIT = 100;

    /** 파일 본문을 가져올 최대 개수. 저장소 전체를 긁으면 rate limit이 먼저 터진다. */
    private static final int FILE_LIMIT = 60;

    private static final int MAX_FILE_BYTES = 200_000;

    private static final Set<String> TEXT_EXT = Set.of(
            "java", "kt", "py", "js", "jsx", "ts", "tsx", "go", "rs", "rb", "c", "h", "cpp", "cs",
            "sql", "sh", "yml", "yaml", "json", "toml", "gradle", "xml", "md", "txt");

    private final RestClient http = RestClient.builder().baseUrl("https://api.github.com").build();

    @Override
    public Source.Type type() {
        return Source.Type.GITHUB;
    }

    /** PR 수집 상한. 커밋과 달리 개수가 적어 넉넉히 잡아도 호출 비용이 낮다. */
    private static final int PR_LIMIT = 50;

    @Override
    public List<RawItem> collect(Source source, String accessToken) {
        String repo = normalizeRepo(source.getExternalRef());
        try {
            List<RawItem> items = new ArrayList<>();
            items.addAll(commits(repo, accessToken));
            items.addAll(pullRequests(repo, accessToken));
            items.addAll(files(repo, accessToken));
            return items;
        } catch (HttpClientErrorException e) {
            throw new IllegalStateException(explain(repo, e.getStatusCode().value()), e);
        }
    }

    /**
     * GitHub 응답 본문을 그대로 화면에 흘리면 읽을 수가 없다.
     *
     * 특히 404는 "없는 저장소"만 뜻하지 않는다. 조직이 서드파티 앱 접근을 제한해 두면
     * 공개 저장소조차 우리 토큰으로는 404로 돌아온다 — 원인이 완전히 다르니 짚어준다.
     */
    private static String explain(String repo, int status) {
        return switch (status) {
            case 404 -> repo + " 에 접근할 수 없습니다. 저장소 주소가 맞는지, 그리고 조직 설정에서 "
                    + "이 앱의 접근을 승인(Grant)했는지 확인해 주세요. 승인 전에는 공개 저장소도 404로 막힙니다.";
            case 401 -> "GitHub 인증이 만료되었습니다. 다시 로그인해 주세요.";
            case 403 -> "GitHub API 호출 한도에 걸렸거나 접근이 거부되었습니다. 잠시 뒤 다시 시도해 주세요.";
            default -> repo + " 수집 실패 (GitHub " + status + ")";
        };
    }

    /**
     * PR은 본문에 "왜 이렇게 고쳤는지"가 남아 있어 포트폴리오 근거로 커밋보다 값이 크다.
     * 병합 여부와 리뷰 논의가 드러나도록 상태와 본문을 함께 담는다.
     */
    private List<RawItem> pullRequests(String repo, String token) {
        JsonNode arr = get(token, "/repos/{repo}/pulls?state=all&per_page=" + PR_LIMIT
                + "&sort=updated&direction=desc", repo);
        List<RawItem> out = new ArrayList<>();
        for (JsonNode pr : arr) {
            out.add(toPullRequestItem(pr));
        }
        return out;
    }

    static RawItem toPullRequestItem(JsonNode pr) {
        int number = pr.path("number").asInt();
        String title = pr.path("title").asString("");
        String body = pr.path("body").asString("");
        // merged_at은 병합 전엔 null로 온다. state는 병합돼도 closed라 따로 구분한다.
        boolean merged = pr.path("merged_at").asString(null) != null;
        String state = merged ? "merged" : pr.path("state").asString("open");

        StringBuilder text = new StringBuilder()
                .append("PR #").append(number).append(' ').append(title).append('\n')
                .append("상태: ").append(state).append('\n');
        if (!body.isBlank()) {
            text.append('\n').append(body);
        }

        return new RawItem(
                Artifact.Type.DOC,
                // 커밋 sha와 겹치지 않도록 접두사를 붙인다.
                "pr-" + number,
                "PR #" + number + " " + title,
                null,
                text.toString(),
                pr.path("user").path("login").asString(null),
                parseInstant(pr.path("created_at").asString(null)),
                pr.path("html_url").asString(null));
    }

    /** org 하나를 통째로 등록했을 때 펼칠 저장소 수 상한. 전부 긁으면 수집이 몇 분씩 걸린다. */
    static final int ORG_REPO_LIMIT = 10;

    /** "https://github.com/owner/repo.git", "owner/repo" 등을 owner/repo로 통일. */
    public static String normalizeRepo(String ref) {
        String[] parts = segments(ref);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("cannot parse repo from: " + ref);
        }
        return parts[0] + "/" + parts[1];
    }

    /** 저장소가 아니라 계정/조직만 가리키는 주소인지. 예: "https://github.com/project-archive-team" */
    public static boolean isOwnerOnly(String ref) {
        String[] parts = segments(ref);
        return parts.length == 1 && !parts[0].isBlank();
    }

    public static String ownerOf(String ref) {
        return segments(ref)[0];
    }

    private static String[] segments(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("github source needs a repo reference");
        }
        String s = ref.trim()
                .replaceFirst("^https?://github\\.com/", "")
                .replaceFirst("\\.git$", "")
                .replaceAll("^/+", "")
                .replaceAll("/+$", "");
        return s.split("/");
    }

    /**
     * 조직(또는 사용자) 아래 저장소 목록. 포트폴리오 근거가 되려면 본인이 실제로 만든 것이어야 하니
     * 포크와 아카이브는 뺀다. 최근에 손댄 것부터 상한만큼만.
     */
    public List<String> listRepos(String owner, String token) {
        JsonNode arr;
        try {
            arr = get(token, "/orgs/{owner}/repos?per_page=100&sort=updated&direction=desc", owner);
        } catch (Exception e) {
            // 조직이 아니라 개인 계정이면 /orgs가 404다.
            arr = get(token, "/users/{owner}/repos?per_page=100&sort=updated&direction=desc", owner);
        }
        return pickRepos(arr);
    }

    static List<String> pickRepos(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode repo : arr) {
            if (repo.path("fork").asBoolean() || repo.path("archived").asBoolean()) {
                continue;
            }
            out.add(repo.path("full_name").asString());
            if (out.size() >= ORG_REPO_LIMIT) {
                break;
            }
        }
        return out;
    }

    private List<RawItem> commits(String repo, String token) {
        JsonNode arr = get(token, "/repos/{repo}/commits?per_page=" + COMMIT_LIMIT, repo);
        List<RawItem> out = new ArrayList<>();
        for (JsonNode c : arr) {
            JsonNode commit = c.path("commit");
            String sha = c.path("sha").asString();
            String message = commit.path("message").asString("");
            String author = commit.path("author").path("name").asString(null);
            Instant date = parseInstant(commit.path("author").path("date").asString(null));
            out.add(new RawItem(
                    Artifact.Type.COMMIT,
                    sha,
                    firstLine(message),
                    null,
                    message,
                    author,
                    date,
                    c.path("html_url").asString(null)));
        }
        return out;
    }

    private List<RawItem> files(String repo, String token) {
        JsonNode repoInfo = get(token, "/repos/{repo}", repo);
        String branch = repoInfo.path("default_branch").asString("main");

        JsonNode tree = get(token, "/repos/{repo}/git/trees/" + branch + "?recursive=1", repo).path("tree");
        List<RawItem> out = new ArrayList<>();
        for (JsonNode node : tree) {
            if (out.size() >= FILE_LIMIT) {
                log.info("repo {} hit file limit {}, skipping the rest", repo, FILE_LIMIT);
                break;
            }
            if (!"blob".equals(node.path("type").asString()) || node.path("size").asInt() > MAX_FILE_BYTES) {
                continue;
            }
            String path = node.path("path").asString();
            if (!isText(path)) {
                continue;
            }
            String content = fileContent(repo, node.path("sha").asString(), token);
            if (content == null) {
                continue;
            }
            out.add(new RawItem(
                    // 문서형 파일은 코드가 아니라 DOC로 — RAG 답변에서 출처 구분이 된다.
                    path.endsWith(".md") || path.endsWith(".txt") ? Artifact.Type.DOC : Artifact.Type.CODE,
                    path,
                    path.substring(path.lastIndexOf('/') + 1),
                    path,
                    content,
                    null,
                    null,
                    "https://github.com/" + repo + "/blob/" + branch + "/" + path));
        }
        return out;
    }

    private String fileContent(String repo, String blobSha, String token) {
        try {
            JsonNode blob = get(token, "/repos/{repo}/git/blobs/" + blobSha, repo);
            if (!"base64".equals(blob.path("encoding").asString())) {
                return null;
            }
            byte[] raw = Base64.getMimeDecoder().decode(blob.path("content").asString());
            String text = new String(raw, StandardCharsets.UTF_8);
            // 바이너리가 확장자만 텍스트인 경우를 걸러낸다.
            return text.indexOf('\0') >= 0 ? null : text;
        } catch (Exception e) {
            log.warn("skip blob {} of {}: {}", blobSha, repo, e.getMessage());
            return null;
        }
    }

    private JsonNode get(String token, String uri, Object... vars) {
        return http.get()
                .uri(uri, vars)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .retrieve()
                .body(JsonNode.class);
    }

    private static boolean isText(String path) {
        int dot = path.lastIndexOf('.');
        return dot > 0 && TEXT_EXT.contains(path.substring(dot + 1).toLowerCase());
    }

    private static String firstLine(String message) {
        int nl = message.indexOf('\n');
        String line = nl < 0 ? message : message.substring(0, nl);
        return line.isBlank() ? "(no message)" : line;
    }

    static Instant parseInstant(String s) {
        return s == null ? null : Instant.parse(s);
    }
}
