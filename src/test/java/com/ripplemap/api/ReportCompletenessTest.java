package com.ripplemap.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.ripplemap.testsupport.IntegrationTestBase;
import com.ripplemap.testsupport.ZipFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** The UI endpoints cap their lists (counts stay exact); downloaded reports always list everything. */
class ReportCompletenessTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void tinyListLimits(DynamicPropertyRegistry registry) {
        registry.add("ripplemap.data-dir", () -> {
            try {
                return Files.createTempDirectory("ripplemap-completeness-").toString();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        registry.add("ripplemap.graph.max-impact-entries", () -> "1");
        registry.add("ripplemap.graph.max-comparison-entries", () -> "1");
    }

    private String download(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andReturn();
        assertThat(result.getResponse().getStatus()).as(url).isEqualTo(200);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void impactReportsListEveryAffectedComponentWhenTheEndpointTruncates() throws Exception {
        long projectId = importFixture("chain")[0];
        String node = "com.acme.chain.Repository";
        MvcResult api = postJson("/api/projects/" + projectId + "/impact-analysis", Map.of("nodeId", node));
        JsonNode result = read(api);
        assertThat(result.path("transitiveCount").asInt()).isEqualTo(2);
        assertThat(result.path("affected")).hasSize(1);
        assertThat(result.path("affectedTruncated").asBoolean()).isTrue();

        String base = "/api/projects/" + projectId + "/reports/impact";
        String query = "?nodeId=" + URLEncoder.encode(node, StandardCharsets.UTF_8);
        String csv = download(base + ".csv" + query);
        assertThat(csv).contains("com.acme.chain.Service,Service,CLASS").contains("com.acme.chain.Controller,Controller,CLASS");
        String html = download(base + ".html" + query);
        assertThat(html).contains("Controller").contains("Service").doesNotContain("The list was truncated");
        JsonNode doc = json.readTree(download(base + ".json" + query));
        assertThat(doc.path("impact").path("affected")).hasSize(2);
        assertThat(doc.path("impact").path("affectedTruncated").asBoolean()).isFalse();
    }

    @Test
    void comparisonReportsListEveryChangeWhenTheEndpointTruncates() throws Exception {
        long[] v1 = importFixture("versions/v1");
        long projectId = v1[0];
        long v2 = read(uploadVersion(projectId, ZipFixtures.zip("versions/v2"), null)).path("analysis").path("id").asLong();
        awaitAnalysis(projectId, v2);
        String p = "com.acme.ver.";

        JsonNode cmp = getJson("/api/projects/" + projectId + "/compare?base=" + v1[1] + "&target=" + v2);
        assertThat(cmp.path("addedEdgeCount").asInt()).isEqualTo(2);
        assertThat(cmp.path("addedEdges")).hasSize(1);
        assertThat(cmp.path("truncated").asBoolean()).isTrue();

        String csv = download("/api/projects/" + projectId + "/reports/comparison.csv?base=" + v1[1] + "&target=" + v2);
        assertThat(csv).contains("added,dependency," + p + "Alpha -> " + p + "Gamma")
                .contains("added,dependency," + p + "Delta -> " + p + "Beta")
                .contains("removed,dependency," + p + "Alpha -> " + p + "Beta")
                .contains("removed,dependency," + p + "Legacy -> " + p + "Gamma");
        String html = download("/api/projects/" + projectId + "/reports/comparison.html?base=" + v1[1] + "&target=" + v2);
        assertThat(html).doesNotContain("Lists are limited");
    }
}
