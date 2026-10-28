package com.ripplemap.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.ripplemap.testsupport.Fixtures;
import com.ripplemap.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Local-directory projects with a configured allowed root: registration, rescans and path authorization. */
class LocalDirectoryIntegrationTest extends IntegrationTestBase {

    private static final Path ROOTS = createDir("ripplemap-local-roots-");
    private static final Path ALLOWED = ROOTS.resolve("workspace");
    private static final Path OUTSIDE = ROOTS.resolve("outside");

    private static Path createDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void localScan(DynamicPropertyRegistry registry) throws IOException {
        Files.createDirectories(ALLOWED);
        Files.createDirectories(OUTSIDE);
        registry.add("ripplemap.data-dir", () -> ROOTS.resolve("data").toString());
        registry.add("ripplemap.local-scan.allowed-roots", ALLOWED::toString);
    }

    @Test
    void registeredDirectoriesAreAnalyzedAndRescannedAfterChanges() throws Exception {
        Path project = Fixtures.copy("chain", ALLOWED.resolve("chain-" + System.nanoTime()));
        MvcResult created = postJson("/api/projects/local", Map.of("name", unique("Local chain"),
                "path", project.toString(), "includeTests", false));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = read(created);
        long projectId = body.path("project").path("id").asLong();
        assertThat(body.path("project").path("sourceType").asText()).isEqualTo("LOCAL_DIRECTORY");
        assertThat(body.path("project").path("localPath").asText()).isEqualTo(project.toRealPath().toString());
        JsonNode first = awaitAnalysis(projectId, body.path("analysis").path("id").asLong());
        assertThat(first.path("counts").path("typeCount").asInt()).isEqualTo(3);
        assertThat(first.path("versionId").isNull()).isTrue();

        // edit the sources on disk: Controller now also uses Repository directly, and a new type appears
        Fixtures.write(project, "src/main/java/com/acme/chain/Audit.java",
                "package com.acme.chain; public class Audit { Repository repository; }");
        MvcResult rescan = postJson("/api/projects/" + projectId + "/analyze", Map.of());
        assertThat(rescan.getResponse().getStatus()).isEqualTo(202);
        assertThat(read(rescan).path("trigger").asText()).isEqualTo("RESCAN");
        long secondId = read(rescan).path("id").asLong();
        JsonNode second = awaitAnalysis(projectId, secondId);
        assertThat(second.path("counts").path("typeCount").asInt()).isEqualTo(4);

        JsonNode cmp = getJson("/api/projects/" + projectId + "/compare?base=" + first.path("id").asLong() + "&target=" + secondId);
        assertThat(cmp.path("addedEntities").get(0).path("id").asText()).isEqualTo("com.acme.chain.Audit");
        assertThat(cmp.path("addedEdges").get(0).path("target").asText()).isEqualTo("com.acme.chain.Repository");
        // the original source directory is never modified by RippleMap
        assertThat(Files.readString(project.resolve("src/main/java/com/acme/chain/Service.java")))
                .isEqualTo(Files.readString(Fixtures.path("chain").resolve("src/main/java/com/acme/chain/Service.java")));
        // local projects take rescans, not uploaded versions
        MvcResult version = uploadVersion(projectId, com.ripplemap.testsupport.ZipFixtures.zip("chain"), null);
        assertThat(version.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(version).path("code").asText()).isEqualTo("NOT_A_ZIP_PROJECT");
    }

    @Test
    void directoriesOutsideTheAllowedRootsAreRejected() throws Exception {
        Path outside = Fixtures.copy("chain", OUTSIDE.resolve("chain-" + System.nanoTime()));
        for (String path : new String[]{outside.toString(), ALLOWED.resolve("..").resolve("outside").toString(),
                ROOTS.toString()}) {
            MvcResult result = postJson("/api/projects/local", Map.of("name", unique("Escape"), "path", path,
                    "includeTests", false));
            assertThat(result.getResponse().getStatus()).as(path).isEqualTo(403);
            assertThat(read(result).path("code").asText()).isEqualTo("PATH_NOT_ALLOWED");
        }
        MvcResult relative = postJson("/api/projects/local", Map.of("name", unique("Relative"), "path", "workspace",
                "includeTests", false));
        assertThat(relative.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(relative).path("fieldErrors").get(0).path("field").asText()).isEqualTo("path");
        MvcResult missing = postJson("/api/projects/local", Map.of("name", unique("Missing"),
                "path", ALLOWED.resolve("does-not-exist").toString(), "includeTests", false));
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
    }

    /** Creates a symbolic link, or on Windows without the privilege a directory junction; aborts if neither works. */
    private static void link(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            if (!System.getProperty("os.name").toLowerCase().contains("win")) {
                org.junit.jupiter.api.Assumptions.abort("Symbolic links cannot be created: " + e.getMessage());
            }
        }
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0 || !Files.exists(link)) {
            org.junit.jupiter.api.Assumptions.abort("Neither a symbolic link nor a junction could be created: " + output);
        }
    }

    @Test
    void linksCannotEscapeTheAllowedRoot() throws Exception {
        Path target = Fixtures.copy("chain", OUTSIDE.resolve("secret-" + System.nanoTime()));
        Path link = ALLOWED.resolve("link-" + System.nanoTime());
        link(link, target);
        MvcResult result = postJson("/api/projects/local", Map.of("name", unique("Link"), "path", link.toString(),
                "includeTests", false));
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(result).path("code").asText()).isEqualTo("PATH_NOT_ALLOWED");
    }

    @Test
    void linksInsideAnAllowedProjectAreNotFollowed() throws Exception {
        Path secret = Fixtures.copy("diamond", OUTSIDE.resolve("secret-" + System.nanoTime()));
        Path project = Fixtures.copy("chain", ALLOWED.resolve("with-link-" + System.nanoTime()));
        link(project.resolve("src/main/java/leak"), secret);
        MvcResult created = postJson("/api/projects/local", Map.of("name", unique("With link"),
                "path", project.toString(), "includeTests", false));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        long projectId = read(created).path("project").path("id").asLong();
        long analysisId = read(created).path("analysis").path("id").asLong();
        JsonNode analysis = awaitAnalysis(projectId, analysisId);
        assertThat(analysis.path("counts").path("typeCount").asInt()).isEqualTo(3);
        assertThat(analysis.path("counts").path("filesSkippedLinks").asInt()).isEqualTo(1);
        JsonNode diagnostics = getJson("/api/projects/" + projectId + "/analyses/" + analysisId + "/diagnostics?category=SKIPPED_LINK");
        assertThat(diagnostics.path("items").get(0).path("filePath").asText()).isEqualTo("src/main/java/leak");
        JsonNode search = getJson("/api/projects/" + projectId + "/nodes?q=diamond");
        assertThat(search.path("total").asInt()).isZero();
    }

    @Test
    void systemInfoReportsLocalScanningAndEnumParametersAreCaseInsensitive() throws Exception {
        mvc.perform(get("/api/system")).andExpect(status().isOk())
                .andExpect(jsonPath("$.localScanEnabled").value(true))
                .andExpect(jsonPath("$.localScanRoots[0]").value(ALLOWED.toRealPath().toString()));
        long projectId = importFixture("chain")[0];
        mvc.perform(get("/api/projects/" + projectId + "/nodes?layer=method&q=describe")).andExpect(status().isOk())
                .andExpect(jsonPath("$.layer").value("METHOD"));
        mvc.perform(get("/api/projects/" + projectId + "/graph?layer=Type&scope=all")).andExpect(status().isOk())
                .andExpect(jsonPath("$.layer").value("TYPE"));
        mvc.perform(get("/api/projects/" + projectId + "/graph?layer=banana")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }
}
