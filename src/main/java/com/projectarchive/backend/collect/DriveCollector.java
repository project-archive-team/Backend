package com.projectarchive.backend.collect;

import tools.jackson.databind.JsonNode;
import com.projectarchive.backend.domain.Artifact;
import com.projectarchive.backend.domain.Source;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drive 폴더를 훑어 텍스트를 뽑는다.
 *
 * Google 네이티브 문서는 export로, 나머지(PDF·PPTX·코드/텍스트 파일)는 내려받아
 * 업로드와 같은 FileParser에 태운다 — 같은 pdf를 두 군데서 다르게 읽을 이유가 없다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DriveCollector implements Collector {

    private static final int FILE_LIMIT = 100;

    /** 하위 폴더 탐색 깊이. 자료는 보통 한두 겹 안에 있고, 더 파면 호출 수만 늘어난다. */
    private static final int MAX_DEPTH = 3;

    /** 내려받을 파일 크기 상한. 큰 바이너리를 통째로 메모리에 올리지 않는다. */
    private static final long MAX_DOWNLOAD_BYTES = 10 * 1024 * 1024L;

    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final String NATIVE_PREFIX = "application/vnd.google-apps.";

    private final FileParser fileParser;

    private final RestClient http = RestClient.builder().baseUrl("https://www.googleapis.com").build();

    @Override
    public Source.Type type() {
        return Source.Type.GDRIVE;
    }

    @Override
    public List<RawItem> collect(Source source, String accessToken) {
        String rootId = normalizeFolderId(source.getExternalRef());

        List<RawItem> out = new ArrayList<>();
        Deque<Folder> queue = new ArrayDeque<>();
        queue.add(new Folder(rootId, 0));

        while (!queue.isEmpty() && out.size() < FILE_LIMIT) {
            Folder folder = queue.poll();
            for (JsonNode f : list(folder.id(), accessToken).path("files")) {
                if (out.size() >= FILE_LIMIT) {
                    log.info("drive folder {} hit file limit {}", rootId, FILE_LIMIT);
                    break;
                }
                String mime = f.path("mimeType").asString("");
                if (FOLDER_MIME.equals(mime)) {
                    if (folder.depth() < MAX_DEPTH) {
                        queue.add(new Folder(f.path("id").asString(), folder.depth() + 1));
                    }
                    continue;
                }
                RawItem item = toItem(f, mime, accessToken);
                if (item != null) {
                    out.add(item);
                }
            }
        }
        return out;
    }

    private record Folder(String id, int depth) {}

    private JsonNode list(String folderId, String token) {
        return http.get()
                .uri(b -> b.path("/drive/v3/files")
                        .queryParam("q", "'" + folderId + "' in parents and trashed = false")
                        .queryParam("fields",
                                "files(id,name,mimeType,size,modifiedTime,webViewLink,owners(displayName))")
                        .queryParam("pageSize", FILE_LIMIT)
                        .build())
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(JsonNode.class);
    }

    private RawItem toItem(JsonNode f, String mime, String token) {
        String id = f.path("id").asString();
        String name = f.path("name").asString("");
        String text = mime.startsWith(NATIVE_PREFIX)
                ? exportNative(id, mime, token)
                : download(id, name, f, token);
        if (text == null || text.isBlank()) {
            return null;
        }
        return new RawItem(
                classify(name),
                id,
                name,
                name,
                text,
                f.path("owners").path(0).path("displayName").asString(null),
                GithubCollector.parseInstant(f.path("modifiedTime").asString(null)),
                f.path("webViewLink").asString(null));
    }

    /** 파일명에 "회의"가 들어가면 회의록, 코드 확장자면 코드, 나머지는 문서. */
    static Artifact.Type classify(String name) {
        if (isMeeting(name)) {
            return Artifact.Type.MEETING;
        }
        String lower = name.toLowerCase();
        boolean document = lower.endsWith(".md") || lower.endsWith(".txt")
                || lower.endsWith(".pdf") || lower.endsWith(".pptx");
        return !document && FileParser.isTextExtension(lower) ? Artifact.Type.CODE : Artifact.Type.DOC;
    }

    /** Google 문서·슬라이드·스프레드시트를 평문으로 뽑는다. */
    private String exportNative(String fileId, String mimeType, String token) {
        String kind = mimeType.substring(NATIVE_PREFIX.length());
        String exportAs = switch (kind) {
            case "document", "presentation" -> "text/plain";
            case "spreadsheet" -> "text/csv";
            default -> null;
        };
        if (exportAs == null) {
            return null;
        }
        try {
            return http.get()
                    .uri("/drive/v3/files/{id}/export?mimeType={mime}", fileId, exportAs)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.warn("drive export failed for {}: {}", fileId, e.getMessage());
            return null;
        }
    }

    /** 업로드된 실제 파일(PDF·PPTX·코드 등)을 내려받아 업로드와 같은 파서에 태운다. */
    private String download(String fileId, String name, JsonNode meta, String token) {
        if (!fileParser.supports(name)) {
            return null;
        }
        long size = meta.path("size").asLong(0);
        if (size > MAX_DOWNLOAD_BYTES) {
            log.info("drive file {} too large ({} bytes), skipping", name, size);
            return null;
        }
        try {
            byte[] bytes = http.get()
                    .uri("/drive/v3/files/{id}?alt=media", fileId)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(byte[].class);
            return bytes == null ? null : fileParser.parse(name, bytes);
        } catch (Exception e) {
            log.warn("drive download failed for {}: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * 사용자는 주소창의 폴더 URL을 그대로 붙여넣는다. 그걸 폴더 ID로 쓰면 Drive가
     * "File not found: ." 를 돌려준다 — 흔한 형태에서 ID만 뽑아낸다.
     */
    public static String normalizeFolderId(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("Google Drive 소스는 폴더 주소 또는 ID가 필요합니다");
        }
        String s = ref.trim();

        Matcher folders = FOLDER_URL.matcher(s);
        if (folders.find()) {
            return folders.group(1);
        }
        Matcher idParam = ID_PARAM.matcher(s);
        if (idParam.find()) {
            return idParam.group(1);
        }
        if (s.startsWith("http")) {
            throw new IllegalArgumentException("폴더 ID를 찾을 수 없는 주소입니다: " + ref);
        }
        return s;
    }

    private static final Pattern FOLDER_URL = Pattern.compile("/folders/([A-Za-z0-9_-]+)");
    private static final Pattern ID_PARAM = Pattern.compile("[?&]id=([A-Za-z0-9_-]+)");

    static boolean isMeeting(String name) {
        String n = name.toLowerCase();
        return n.contains("회의") || n.contains("meeting") || n.contains("minutes");
    }
}
