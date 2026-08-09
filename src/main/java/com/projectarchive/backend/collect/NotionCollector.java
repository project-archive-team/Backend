package com.projectarchive.backend.collect;

import tools.jackson.databind.JsonNode;
import com.projectarchive.backend.domain.Artifact;
import com.projectarchive.backend.domain.Source;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** integration에 공유된 페이지를 훑어 블록 텍스트를 뽑는다. */
@Component
public class NotionCollector implements Collector {

    private static final String VERSION = "2022-06-28";

    /** 중첩 블록을 따라가는 깊이 상한. 무한 재귀 방지 겸 비용 상한. */
    private static final int MAX_DEPTH = 3;

    private final RestClient http = RestClient.builder().baseUrl("https://api.notion.com").build();

    @Override
    public Source.Type type() {
        return Source.Type.NOTION;
    }

    @Override
    public List<RawItem> collect(Source source, String accessToken) {
        List<RawItem> out = new ArrayList<>();
        for (JsonNode page : pages(source.getExternalRef(), accessToken)) {
            String id = page.path("id").asString();
            String title = title(page);
            String text = blockText(id, accessToken, 0);
            out.add(new RawItem(
                    DriveCollector.isMeeting(title) ? Artifact.Type.MEETING : Artifact.Type.DOC,
                    id,
                    title,
                    null,
                    text,
                    null,
                    GithubCollector.parseInstant(page.path("last_edited_time").asString(null)),
                    page.path("url").asString(null)));
        }
        return out;
    }

    /**
     * 사용자는 브라우저 주소를 그대로 붙여넣는다. Notion URL은 제목 뒤에 32자리 id가 붙는 형태라
     * 그대로 쓰면 페이지를 못 찾는다 — id만 뽑아낸다. 비어 있으면 공유된 페이지 전체가 대상.
     */
    public static String normalizePageId(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String s = ref.trim();
        Matcher dashed = DASHED_UUID.matcher(s);
        if (dashed.find()) {
            return dashed.group();
        }
        Matcher plain = PLAIN_ID.matcher(s);
        String last = null;
        while (plain.find()) {
            last = plain.group(1);
        }
        if (last != null) {
            return last;
        }
        if (s.startsWith("http")) {
            throw new IllegalArgumentException("페이지 ID를 찾을 수 없는 주소입니다: " + ref);
        }
        return s;
    }

    private static final Pattern DASHED_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern PLAIN_ID = Pattern.compile("([0-9a-fA-F]{32})");

    /** externalRef가 있으면 그 페이지 하나, 없으면 integration에 공유된 페이지 전부. */
    private List<JsonNode> pages(String externalRef, String token) {
        List<JsonNode> out = new ArrayList<>();
        String pageId = normalizePageId(externalRef);
        if (pageId != null) {
            out.add(http.get().uri("/v1/pages/{id}", pageId)
                    .headers(h -> auth(h, token)).retrieve().body(JsonNode.class));
            return out;
        }
        JsonNode res = http.post().uri("/v1/search")
                .headers(h -> auth(h, token))
                .body("{\"filter\":{\"property\":\"object\",\"value\":\"page\"},\"page_size\":50}")
                .retrieve().body(JsonNode.class);
        res.path("results").forEach(out::add);
        return out;
    }

    private String blockText(String blockId, String token, int depth) {
        if (depth > MAX_DEPTH) {
            return "";
        }
        JsonNode res = http.get().uri("/v1/blocks/{id}/children?page_size=100", blockId)
                .headers(h -> auth(h, token)).retrieve().body(JsonNode.class);

        StringBuilder sb = new StringBuilder();
        for (JsonNode block : res.path("results")) {
            String type = block.path("type").asString();
            for (JsonNode rt : block.path(type).path("rich_text")) {
                sb.append(rt.path("plain_text").asString(""));
            }
            sb.append('\n');
            if (block.path("has_children").asBoolean()) {
                sb.append(blockText(block.path("id").asString(), token, depth + 1));
            }
        }
        return sb.toString();
    }

    private static String title(JsonNode page) {
        // 페이지 제목 속성의 이름은 DB마다 달라서 title 타입인 첫 속성을 집는다.
        for (JsonNode prop : page.path("properties")) {
            if ("title".equals(prop.path("type").asString())) {
                String t = prop.path("title").path(0).path("plain_text").asString("");
                if (!t.isBlank()) {
                    return t;
                }
            }
        }
        return "(untitled)";
    }

    private static void auth(org.springframework.http.HttpHeaders h, String token) {
        h.setBearerAuth(token);
        h.set("Notion-Version", VERSION);
        h.set("Content-Type", "application/json");
    }
}
