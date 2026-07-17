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
        String folderId = source.getExternalRef();
        if (folderId == null || folderId.isBlank()) {
            throw new IllegalArgumentException("drive source needs a folder id");
        }
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
