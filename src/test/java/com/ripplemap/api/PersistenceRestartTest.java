package com.ripplemap.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ripplemap.RippleMapApplication;
import com.ripplemap.security.CsrfTokenService;
import com.ripplemap.testsupport.ZipFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Real restart: a file-backed H2 database is written by one application context, the context is closed, and a
 * second context started on the same data directory must return identical projects, snapshots and graphs.
 * All interaction happens over real HTTP.
 */
class PersistenceRestartTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path dataDir;

    private ConfigurableApplicationContext start() {
        // command-line arguments take precedence over application.yml (builder default properties would not)
        return new SpringApplicationBuilder(RippleMapApplication.class)
                .run("--ripplemap.data-dir=" + dataDir, "--server.port=0");
    }

    private static String base(ConfigurableApplicationContext context) {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return "http://127.0.0.1:" + port;
    }

    private JsonNode get(String url) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(url).isEqualTo(200);
        return json.readTree(response.body());
    }

    private JsonNode postImpact(String base, long projectId, String token, String node) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/projects/" + projectId + "/impact-analysis"))
                .header("Content-Type", "application/json").header(CsrfTokenService.HEADER, token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"nodeId\":\"" + node + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    @Test
    void projectsSnapshotsAndGraphsSurviveARestart() throws Exception {
        long projectId;
        long analysisId;
        JsonNode graphBefore;
        JsonNode countsBefore;
        JsonNode impactBefore;
        String dbFile;
        try (ConfigurableApplicationContext first = start()) {
            String base = base(first);
            String token = first.getBean(CsrfTokenService.class).token();
            String boundary = "----ripplemap" + UUID.randomUUID();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\nRestart Demo\r\n").getBytes(StandardCharsets.UTF_8));
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"syntax.zip\"\r\n"
                    + "Content-Type: application/zip\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.writeBytes(ZipFixtures.zip("syntax"));
            body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpResponse<String> imported = http.send(HttpRequest.newBuilder(URI.create(base + "/api/projects/import"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header(CsrfTokenService.HEADER, token)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(imported.statusCode()).isEqualTo(201);
            JsonNode response = json.readTree(imported.body());
            projectId = response.path("project").path("id").asLong();
            analysisId = response.path("analysis").path("id").asLong();
            String statusUrl = base + "/api/projects/" + projectId + "/analyses/" + analysisId;
            await().atMost(Duration.ofSeconds(60)).until(() -> get(statusUrl).path("hasResults").asBoolean());
            countsBefore = get(statusUrl).path("counts");
            graphBefore = get(base + "/api/projects/" + projectId + "/graph?layer=METHOD");
            impactBefore = postImpact(base, projectId, token, "com.acme.model.Shape");
            dbFile = dataDir.resolve("db").resolve("ripplemap.mv.db").toString();
        }
        assertThat(Files.size(Path.of(dbFile))).isPositive();

        try (ConfigurableApplicationContext second = start()) {
            String base = base(second);
            JsonNode project = get(base + "/api/projects/" + projectId);
            assertThat(project.path("project").path("name").asText()).isEqualTo("Restart Demo");
            assertThat(project.path("project").path("defaultAnalysisId").asLong()).isEqualTo(analysisId);
            JsonNode analysis = get(base + "/api/projects/" + projectId + "/analyses/" + analysisId);
            assertThat(analysis.path("status").asText()).isEqualTo("COMPLETED");
            assertThat(analysis.path("counts")).isEqualTo(countsBefore);
            JsonNode graphAfter = get(base + "/api/projects/" + projectId + "/graph?layer=METHOD");
            assertThat(graphAfter.path("nodes")).isEqualTo(graphBefore.path("nodes"));
            assertThat(graphAfter.path("edges")).isEqualTo(graphBefore.path("edges"));
            String token = second.getBean(CsrfTokenService.class).token();
            JsonNode impactAfter = postImpact(base, projectId, token, "com.acme.model.Shape");
            assertThat(impactAfter.path("score")).isEqualTo(impactBefore.path("score"));
            assertThat(impactAfter.path("affected")).isEqualTo(impactBefore.path("affected"));
            JsonNode node = get(base + "/api/projects/" + projectId + "/node?nodeId="
                    + URLEncoder.encode("com.acme.util.Formatter#format(double,int)", StandardCharsets.UTF_8));
            assertThat(node.path("entity").path("signature").asText()).isEqualTo("format(double, int) : String");
        }
    }
}
