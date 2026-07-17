package com.projectarchive.backend.collect;

import tools.jackson.databind.JsonNode;
import com.projectarchive.backend.domain.Artifact;
import com.projectarchive.backend.domain.Source;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
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

    @Override
    public List<RawItem> collect(Source source, String accessToken) {
        String repo = normalizeRepo(source.getExternalRef());
        List<RawItem> items = new ArrayList<>();
        items.addAll(commits(repo, accessToken));
        items.addAll(files(repo, accessToken));
        return items;
    }

    /** "https://github.com/owner/repo.git", "owner/repo" 등을 owner/repo로 통일. */
    static String normalizeRepo(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("github source needs a repo reference");
        }
        String s = ref.trim();
        s = s.replaceFirst("^https?://github\\.com/", "");
        s = s.replaceFirst("\\.git$", "");
        s = s.replaceAll("^/+", "").replaceAll("/+$", "");
        String[] parts = s.split("/");
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("cannot parse repo from: " + ref);
        }
        return parts[0] + "/" + parts[1];
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
