package com.ripplemap.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.ripplemap.testsupport.IntegrationTestBase;
import com.ripplemap.testsupport.RawZipBuilder;
import com.ripplemap.testsupport.ZipFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Project lifecycle through the REST API: import, validation, security, versions, deletion. */
class ProjectApiIntegrationTest extends IntegrationTestBase {

    @Test
    void healthAndPagesAreServedWithSecurityHeaders() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mvc.perform(get("/h2-console")).andExpect(status().isNotFound());
        for (String page : List.of("/", "/projects", "/import", "/settings")) {
            mvc.perform(get(page)).andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("script-src 'self'")))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unsafe-inline"))));
        }
        // the API documentation is served with its own policy (Swagger UI needs inline code), but still same-origin only
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("script-src 'self' 'unsafe-inline'")))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
        mvc.perform(get("/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("RippleMap API"))
                .andExpect(jsonPath("$.paths['/api/projects/{projectId}/impact-analysis']").exists());
    }

    @Test
    void importRunsTheRealAnalysisAndPersistsResults() throws Exception {
        String name = unique("Chain");
        MvcResult result = importZip(name, ZipFixtures.zip("chain"), false);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = read(result);
        long projectId = body.path("project").path("id").asLong();
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/projects/" + projectId);
        assertThat(body.path("version").path("javaFileCount").asInt()).isEqualTo(3);
        assertThat(body.path("analysis").path("status").asText()).isIn("PENDING", "RUNNING", "COMPLETED");

        JsonNode analysis = awaitAnalysis(projectId, body.path("analysis").path("id").asLong());
        assertThat(analysis.path("status").asText()).isEqualTo("COMPLETED");
        JsonNode counts = analysis.path("counts");
        assertThat(counts.path("filesParsed").asInt()).isEqualTo(3);
        assertThat(counts.path("typeCount").asInt()).isEqualTo(3);
        assertThat(counts.path("typeDependencyCount").asInt()).isEqualTo(2);
        assertThat(counts.path("typeRelationshipCount").asInt()).isEqualTo(6);
        assertThat(counts.path("referencesUnresolved").asLong()).isZero();

        JsonNode detail = getJson("/api/projects/" + projectId);
        assertThat(detail.path("project").path("name").asText()).isEqualTo(name);
        assertThat(detail.path("project").path("defaultAnalysisId").asLong()).isEqualTo(analysis.path("id").asLong());
        assertThat(detail.path("versions")).hasSize(1);
        assertThat(detail.path("analysisActive").asBoolean()).isFalse();

        JsonNode list = getJson("/api/projects");
        assertThat(list.findValuesAsText("name")).contains(name);
    }

    @Test
    void importValidatesInput() throws Exception {
        byte[] zip = ZipFixtures.zip("chain");
        MvcResult blank = importZip("   ", zip, false);
        assertThat(blank.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(blank).path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(read(blank).path("fieldErrors").get(0).path("field").asText()).isEqualTo("name");

        MvcResult badChars = importZip("bad<script>name", zip, false);
        assertThat(badChars.getResponse().getStatus()).isEqualTo(400);

        MvcResult tooLong = importZip("x".repeat(101), zip, false);
        assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);

        String name = unique("Duplicate");
        importFixture("chain"); // unrelated project
        assertThat(importZip(name, zip, false).getResponse().getStatus()).isEqualTo(201);
        MvcResult duplicate = importZip(name.toUpperCase(), zip, false);
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(duplicate).path("code").asText()).isEqualTo("PROJECT_NAME_TAKEN");

        MvcResult missingFile = mvc.perform(withCsrf(multipart("/api/projects/import").param("name", unique("NoFile")))).andReturn();
        assertThat(missingFile.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(missingFile).path("code").asText()).isEqualTo("MISSING_PARAMETER");
    }

    @Test
    void maliciousAndInvalidArchivesAreRejectedAndCleanedUp() throws Exception {
        Map<String, byte[]> archives = Map.of(
                "ARCHIVE_REJECTED_INVALID_ARCHIVE", "not a zip".getBytes(),
                "ARCHIVE_REJECTED_EMPTY_ARCHIVE", new RawZipBuilder().build(),
                "ARCHIVE_REJECTED_NO_JAVA_FILES", new RawZipBuilder().add("readme.txt", "x").build(),
                "ARCHIVE_REJECTED_PATH_TRAVERSAL", new RawZipBuilder().add("../../Evil.java", "class E {}").build(),
                "ARCHIVE_REJECTED_ABSOLUTE_PATH", new RawZipBuilder().add("/tmp/Evil.java", "class E {}").build(),
                "ARCHIVE_REJECTED_DUPLICATE_ENTRY", new RawZipBuilder().add("A.java", "class A {}").add("A.java", "class B {}").build());
        List<String> importsBefore = listing(DATA_DIR.resolve("imports"));
        for (Map.Entry<String, byte[]> e : archives.entrySet()) {
            MvcResult result = importZip(unique("Evil"), e.getValue(), false);
            assertThat(result.getResponse().getStatus()).as(e.getKey()).isEqualTo(422);
            assertThat(read(result).path("code").asText()).isEqualTo(e.getKey());
            assertThat(read(result).path("message").asText()).isNotBlank();
        }
        assertThat(listing(DATA_DIR.resolve("imports"))).containsExactlyInAnyOrderElementsOf(importsBefore);
        assertThat(listing(DATA_DIR.resolve("tmp"))).isEmpty();
        assertThat(Files.exists(DATA_DIR.resolve("Evil.java"))).isFalse();
        assertThat(Files.exists(DATA_DIR.getParent().resolve("Evil.java"))).isFalse();
    }

    @Test
    void oversizedUploadIsRejected() throws Exception {
        byte[] big = new byte[51 * 1024 * 1024];
        MvcResult result = importZip(unique("Huge"), big, false);
        assertThat(result.getResponse().getStatus()).isEqualTo(413);
        assertThat(read(result).path("code").asText()).isEqualTo("UPLOAD_TOO_LARGE");
    }

    @Test
    void stateChangingRequestsRequireCsrfTokenSameOriginAndLocalHost() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "p.zip", "application/zip", ZipFixtures.zip("chain"));
        mvc.perform(multipart("/api/projects/import").file(file).param("name", unique("NoToken")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
        mvc.perform(multipart("/api/projects/import").file(file).param("name", unique("BadToken"))
                        .header("X-RippleMap-CSRF", "wrong"))
                .andExpect(status().isForbidden());
        mvc.perform(withCsrf(multipart("/api/projects/import").file(file).param("name", unique("Origin")))
                        .header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"));
        mvc.perform(withCsrf(multipart("/api/projects/import").file(file).param("name", unique("Site")))
                        .header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CROSS_SITE_REQUEST"));
        mvc.perform(get("/api/projects").header("Host", "attacker.example:8080"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HOST_NOT_ALLOWED"));
        mvc.perform(get("/api/projects").header("Host", "localhost:8080")).andExpect(status().isOk());
        mvc.perform(get("/api/projects").header("Host", "127.0.0.1:8080")).andExpect(status().isOk());
        // GET never needs the token
        mvc.perform(get("/api/dashboard")).andExpect(status().isOk());
    }

    @Test
    void unknownResourcesReturnConsistentErrors() throws Exception {
        mvc.perform(get("/api/projects/999999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/api/projects/999999"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(get("/api/projects/abc")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/does-not-exist")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").exists());
        mvc.perform(get("/projects/999999")).andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        mvc.perform(withCsrf(post("/api/projects/1/impact-analysis").contentType(MediaType.APPLICATION_JSON).content("{bad json")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(withCsrf(delete("/api/projects"))).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void projectMetadataCanBeEdited() throws Exception {
        long projectId = importFixture("chain")[0];
        String newName = unique("Renamed");
        mvc.perform(withCsrf(patch("/api/projects/" + projectId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + newName + "\",\"description\":\"Line one\\nLine two\",\"includeTests\":true}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(newName))
                .andExpect(jsonPath("$.description").value("Line one\nLine two"))
                .andExpect(jsonPath("$.includeTests").value(true));
        mvc.perform(withCsrf(patch("/api/projects/" + projectId).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletingAProjectRemovesItsDataAndStoredSources() throws Exception {
        long[] ids = importFixture("diamond");
        long projectId = ids[0];
        List<String> before = listing(DATA_DIR.resolve("imports"));
        mvc.perform(withCsrf(delete("/api/projects/" + projectId))).andExpect(status().isNoContent());
        mvc.perform(get("/api/projects/" + projectId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/" + projectId + "/graph")).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/" + projectId + "/analyses/" + ids[1])).andExpect(status().isNotFound());
        assertThat(listing(DATA_DIR.resolve("imports"))).hasSize(before.size() - 1);
        mvc.perform(withCsrf(delete("/api/projects/" + projectId))).andExpect(status().isNotFound());
    }

    @Test
    void reanalyzingAStoredVersionCreatesANewImmutableSnapshot() throws Exception {
        long[] ids = importFixture("samename");
        long projectId = ids[0];
        JsonNode first = getJson("/api/projects/" + projectId + "/analyses/" + ids[1]);
        assertThat(first.path("counts").path("typeCount").asInt()).isEqualTo(5);

        MvcResult started = postJson("/api/projects/" + projectId + "/analyze", Map.of("includeTests", true));
        assertThat(started.getResponse().getStatus()).isEqualTo(202);
        long secondId = read(started).path("id").asLong();
        assertThat(read(started).path("trigger").asText()).isEqualTo("REANALYZE");
        JsonNode second = awaitAnalysis(projectId, secondId);
        assertThat(second.path("sequenceNumber").asInt()).isEqualTo(2);
        assertThat(second.path("includeTests").asBoolean()).isTrue();
        assertThat(second.path("counts").path("typeCount").asInt()).isEqualTo(6);

        // the first snapshot is unchanged and still addressable
        JsonNode firstAgain = getJson("/api/projects/" + projectId + "/analyses/" + ids[1]);
        assertThat(firstAgain.path("counts")).isEqualTo(first.path("counts"));
        JsonNode oldGraph = getJson("/api/projects/" + projectId + "/graph?analysisId=" + ids[1]);
        assertThat(oldGraph.path("analysisId").asLong()).isEqualTo(ids[1]);
        assertThat(oldGraph.path("nodes")).hasSize(5);
        JsonNode newGraph = getJson("/api/projects/" + projectId + "/graph");
        assertThat(newGraph.path("analysisId").asLong()).isEqualTo(secondId);
        assertThat(newGraph.path("nodes")).hasSize(6);
    }

    @Test
    void secondAnalysisWhileOneIsActiveIsRefused() throws Exception {
        MvcResult imported = importZip(unique("Busy"), ZipFixtures.zip("syntax"), false);
        long projectId = read(imported).path("project").path("id").asLong();
        long analysisId = read(imported).path("analysis").path("id").asLong();
        MvcResult second = postJson("/api/projects/" + projectId + "/analyze", Map.of());
        if (second.getResponse().getStatus() == 409) {
            assertThat(read(second).path("code").asText()).isEqualTo("ANALYSIS_IN_PROGRESS");
            awaitAnalysis(projectId, analysisId);
        } else {
            // the first analysis finished before the second request arrived
            assertThat(second.getResponse().getStatus()).isEqualTo(202);
            awaitAnalysis(projectId, read(second).path("id").asLong());
        }
    }

    @Test
    void snapshotsCanBeDeletedIndividually() throws Exception {
        long[] ids = importFixture("cycle");
        long projectId = ids[0];
        long second = read(postJson("/api/projects/" + projectId + "/analyze", Map.of())).path("id").asLong();
        awaitAnalysis(projectId, second);
        mvc.perform(withCsrf(delete("/api/projects/" + projectId + "/analyses/" + ids[1]))).andExpect(status().isNoContent());
        mvc.perform(get("/api/projects/" + projectId + "/analyses/" + ids[1])).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/" + projectId + "/graph?analysisId=" + ids[1])).andExpect(status().isNotFound());
        assertThat(getJson("/api/projects/" + projectId + "/graph").path("analysisId").asLong()).isEqualTo(second);
    }

    @Test
    void localDirectoryProjectsAreDisabledByDefault() throws Exception {
        MvcResult result = postJson("/api/projects/local", Map.of("name", unique("Local"), "path", DATA_DIR.toString(),
                "includeTests", false));
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(result).path("code").asText()).isEqualTo("LOCAL_SCAN_DISABLED");
    }

    @Test
    void zipProjectsCannotBeRescannedAsDirectoriesAndVersionsNeedZipProjects() throws Exception {
        long projectId = importFixture("chain")[0];
        MvcResult wrongVersion = postJson("/api/projects/" + projectId + "/analyze", Map.of("versionId", 987654));
        assertThat(wrongVersion.getResponse().getStatus()).isEqualTo(404);
        assertThat(read(wrongVersion).path("code").asText()).isEqualTo("VERSION_NOT_FOUND");
    }

    private static List<String> listing(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return new ArrayList<>(s.map(p -> p.getFileName().toString()).toList());
        }
    }
}
