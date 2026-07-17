package com.projectarchive.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 컨텍스트가 뜨는 것 자체가 검증이다: Flyway가 실제 Postgres에 스키마를 만들고
 * hibernate ddl-auto=validate가 엔티티와 대조한다. 둘이 어긋나면 여기서 죽는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ApiSmokeTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvcTester mvc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void tokenGrantsAccessAndItsAbsenceBlocks() throws Exception {
        String token = signup("dev@example.com");

        assertThat(mvc.get().uri("/api/projects").header("Authorization", "Bearer " + token).exchange())
                .hasStatusOk();

        assertThat(mvc.get().uri("/api/projects").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void createProjectAndReadItBack() throws Exception {
        String token = signup("owner@example.com");

        var created = mvc.post().uri("/api/projects")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"캡스톤 — 아카이브봇","period":"8주","members":4,"techStack":["Spring","FastAPI"]}
                        """)
                .exchange();

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).bodyJson().extractingPath("$.name").isEqualTo("캡스톤 — 아카이브봇");
        assertThat(created).bodyJson().extractingPath("$.status").isEqualTo("PENDING");
        assertThat(created).bodyJson().extractingPath("$.techStack").asArray().containsExactly("Spring", "FastAPI");

        // 목록에도 잡혀야 한다.
        assertThat(mvc.get().uri("/api/projects").header("Authorization", "Bearer " + token).exchange())
                .bodyJson().extractingPath("$[0].name").isEqualTo("캡스톤 — 아카이브봇");
    }

    @Test
    void syncWithoutSourcesIsRejected() throws Exception {
        String token = signup("nosource@example.com");
        long id = createProject(token, "소스 없는 프로젝트");

        assertThat(mvc.post().uri("/api/projects/" + id + "/sync")
                .header("Authorization", "Bearer " + token).exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void githubSourceRequiresRepoUrl() throws Exception {
        String token = signup("gh@example.com");
        long id = createProject(token, "깃허브 프로젝트");

        assertThat(mvc.post().uri("/api/projects/" + id + "/sources")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"GITHUB\"}")
                .exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void othersProjectIsNotVisible() throws Exception {
        String alice = signup("alice@example.com");
        String bob = signup("bob@example.com");
        long id = createProject(alice, "alice의 프로젝트");

        assertThat(mvc.get().uri("/api/projects/" + id).header("Authorization", "Bearer " + bob).exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        signup("dup@example.com");
        assertThat(post("/api/auth/signup",
                "{\"email\":\"dup@example.com\",\"password\":\"password123\",\"name\":\"또다른\"}"))
                .hasStatus(HttpStatus.CONFLICT);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String signup(String email) throws Exception {
        var res = post("/api/auth/signup",
                "{\"email\":\"%s\",\"password\":\"password123\",\"name\":\"tester\"}".formatted(email));
        assertThat(res).hasStatus(HttpStatus.CREATED);
        return json.readTree(res.getResponse().getContentAsString()).path("accessToken").asString();
    }

    private long createProject(String token, String name) throws Exception {
        var res = mvc.post().uri("/api/projects")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"members\":1}".formatted(name))
                .exchange();
        assertThat(res).hasStatus(HttpStatus.CREATED);
        return json.readTree(res.getResponse().getContentAsString()).path("id").asLong();
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult post(String uri, String body) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }
}
