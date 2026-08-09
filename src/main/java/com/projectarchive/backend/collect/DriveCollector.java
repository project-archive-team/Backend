package com.projectarchive.backend.collect;

import tools.jackson.databind.JsonNode;
import com.projectarchive.backend.domain.Artifact;
import com.projectarchive.backend.domain.Source;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Drive 폴더 하나를 훑어 Docs/Slides를 평문으로 export한다. */
@Component
@Slf4j
public class DriveCollector implements Collector {

    private static final int FILE_LIMIT = 100;

    private final RestClient http = RestClient.builder().baseUrl("https://www.googleapis.com").build();

    @Override
    public Source.Type type() {
        return Source.Type.GDRIVE;
    }

    @Override
    public List<RawItem> collect(Source source, String accessToken) {
        String folderId = normalizeFolderId(source.getExternalRef());
        JsonNode res = http.get()
                .uri(b -> b.path("/drive/v3/files")
                        .queryParam("q", "'" + folderId + "' in parents and trashed = false")
                        .queryParam("fields", "files(id,name,mimeType,modifiedTime,webViewLink,owners(displayName))")
                        .queryParam("pageSize", FILE_LIMIT)
                        .build())
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(JsonNode.class);

        List<RawItem> out = new ArrayList<>();
        for (JsonNode f : res.path("files")) {
            String mime = f.path("mimeType").asString();
            String text = export(f.path("id").asString(), mime, accessToken);
            if (text == null) {
                continue;
            }
            String name = f.path("name").asString();
            out.add(new RawItem(
                    // 파일명에 "회의"가 들어가면 회의록으로 본다.
                    isMeeting(name) ? Artifact.Type.MEETING : Artifact.Type.DOC,
                    f.path("id").asString(),
                    name,
                    name,
                    text,
                    f.path("owners").path(0).path("displayName").asString(null),
                    GithubCollector.parseInstant(f.path("modifiedTime").asString(null)),
                    f.path("webViewLink").asString(null)));
        }
        return out;
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

    /** Google 네이티브 문서만 평문 추출. 업로드된 PDF/PPTX는 UploadCollector 경로를 쓴다. */
    private String export(String fileId, String mimeType, String token) {
        if (!mimeType.startsWith("application/vnd.google-apps.")) {
            return null;
        }
        String kind = mimeType.substring("application/vnd.google-apps.".length());
        if (!kind.equals("document") && !kind.equals("presentation")) {
            return null;
        }
        try {
            return http.get()
                    .uri("/drive/v3/files/{id}/export?mimeType=text/plain", fileId)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.warn("drive export failed for {}: {}", fileId, e.getMessage());
            return null;
        }
    }

    static boolean isMeeting(String name) {
        String n = name.toLowerCase();
        return n.contains("회의") || n.contains("meeting") || n.contains("minutes");
    }
}
