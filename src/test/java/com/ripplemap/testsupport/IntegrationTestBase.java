package com.ripplemap.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ripplemap.security.CsrfTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Full application context with a file-backed H2 database in a fresh temporary data directory.
 * Requests go through the real filter chain (host check, CSRF) via MockMvc.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    protected static final Path DATA_DIR = createDataDir();

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected CsrfTokenService csrf;

    private static Path createDataDir() {
        try {
            return Files.createTempDirectory("ripplemap-it-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("ripplemap.data-dir", () -> DATA_DIR.toString());
    }

    protected static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    protected <T extends MockHttpServletRequestBuilder> T withCsrf(T builder) {
        builder.header(CsrfTokenService.HEADER, csrf.token());
        return builder;
    }

    protected JsonNode read(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return body.isEmpty() ? null : json.readTree(body);
    }

    protected MvcResult importZip(String name, byte[] zip, boolean includeTests) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/projects/import")
                .file(new MockMultipartFile("file", "project.zip", "application/zip", zip));
        request.param("name", name).param("description", "Integration test project")
                .param("includeTests", String.valueOf(includeTests));
        return mvc.perform(withCsrf(request)).andReturn();
    }

    /** Imports a fixture and waits until its first analysis has finished. Returns {projectId, analysisId}. */
    protected long[] importFixture(String fixture) throws Exception {
        MvcResult result = importZip(unique(fixture.replace('/', ' ')), ZipFixtures.zip(fixture), false);
        if (result.getResponse().getStatus() != 201) {
            throw new AssertionError("Import failed: " + result.getResponse().getContentAsString());
        }
        JsonNode body = read(result);
        long projectId = body.path("project").path("id").asLong();
        long analysisId = body.path("analysis").path("id").asLong();
        JsonNode finished = awaitAnalysis(projectId, analysisId);
        if (!finished.path("hasResults").asBoolean()) {
            throw new AssertionError("Analysis did not produce results: " + finished);
        }
        return new long[]{projectId, analysisId};
    }

    protected MvcResult uploadVersion(long projectId, byte[] zip, String label) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/projects/" + projectId + "/versions")
                .file(new MockMultipartFile("file", "v.zip", "application/zip", zip));
        if (label != null) {
            request.param("label", label);
        }
        return mvc.perform(withCsrf(request)).andReturn();
    }

    protected JsonNode awaitAnalysis(long projectId, long analysisId) {
        AtomicReference<JsonNode> last = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(100)).until(() -> {
            JsonNode node = getJson("/api/projects/" + projectId + "/analyses/" + analysisId);
            last.set(node);
            String status = node.path("status").asText();
            return !status.equals("PENDING") && !status.equals("RUNNING");
        });
        return last.get();
    }

    protected JsonNode getJson(String url) throws Exception {
        return read(mvc.perform(get(url).accept(MediaType.APPLICATION_JSON)).andReturn());
    }

    protected MvcResult postJson(String url, Object body) throws Exception {
        return mvc.perform(withCsrf(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))).andReturn();
    }
}
