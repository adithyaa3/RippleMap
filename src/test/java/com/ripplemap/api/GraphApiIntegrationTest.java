package com.ripplemap.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.ripplemap.testsupport.IntegrationTestBase;
import com.ripplemap.testsupport.ZipFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Graph, impact, cycle and comparison endpoints against snapshots produced by the real pipeline. */
class GraphApiIntegrationTest extends IntegrationTestBase {

    private static final String C = "com.acme.chain.";

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Set<String> edges(JsonNode graph) {
        Set<String> result = new TreeSet<>();
        graph.path("edges").forEach(e -> result.add(e.path("source").asText() + "->" + e.path("target").asText()));
        return result;
    }

    private static List<String> ids(JsonNode array, String field) {
        List<String> result = new ArrayList<>();
        array.forEach(n -> result.add(field == null ? n.asText() : n.path(field).asText()));
        return result;
    }

    @Test
    void chainGraphAndDirectionalQueries() throws Exception {
        long projectId = importFixture("chain")[0];
        String base = "/api/projects/" + projectId;

        JsonNode graph = getJson(base + "/graph");
        assertThat(graph.path("layer").asText()).isEqualTo("TYPE");
        assertThat(graph.path("truncated").asBoolean()).isFalse();
        assertThat(graph.path("totalNodes").asInt()).isEqualTo(3);
        assertThat(edges(graph)).containsExactly(C + "Controller->" + C + "Service", C + "Service->" + C + "Repository");

        JsonNode deps = getJson(base + "/dependencies?nodeId=" + enc(C + "Controller"));
        assertThat(ids(deps.path("items"), null)).hasSize(1);
        assertThat(deps.path("items").get(0).path("entity").path("id").asText()).isEqualTo(C + "Service");
        JsonNode transitiveDeps = getJson(base + "/dependencies?transitive=true&nodeId=" + enc(C + "Controller"));
        assertThat(transitiveDeps.path("total").asInt()).isEqualTo(2);
        JsonNode dependents = getJson(base + "/dependents?nodeId=" + enc(C + "Controller"));
        assertThat(dependents.path("total").asInt()).isZero();
        JsonNode repoDependents = getJson(base + "/dependents?transitive=true&nodeId=" + enc(C + "Repository"));
        assertThat(repoDependents.path("total").asInt()).isEqualTo(2);
        assertThat(repoDependents.path("items").get(1).path("path").toString())
                .isEqualTo("[\"" + C + "Controller\",\"" + C + "Service\",\"" + C + "Repository\"]");

        JsonNode node = getJson(base + "/node?nodeId=" + enc(C + "Service"));
        assertThat(node.path("entity").path("kind").asText()).isEqualTo("CLASS");
        assertThat(node.path("entity").path("filePath").asText()).isEqualTo("chain/src/main/java/com/acme/chain/Service.java");
        assertThat(node.path("dependencyCount").asInt()).isEqualTo(1);
        assertThat(node.path("dependentCount").asInt()).isEqualTo(1);
        assertThat(ids(node.path("dependencies").get(0).path("kinds"), null))
                .containsExactlyInAnyOrder("FIELD_TYPE", "OBJECT_CREATION", "METHOD_CALL");
        assertThat(node.path("entity").path("metrics").path("transitiveDependents").asInt()).isEqualTo(1);
        assertThat(ids(node.path("members"), "id")).contains(C + "Service#describe(long)", C + "Service#repository");

        JsonNode edge = getJson(base + "/edge?source=" + enc(C + "Controller") + "&target=" + enc(C + "Service"));
        assertThat(edge.path("referenceCount").asInt()).isEqualTo(3);
        JsonNode call = null;
        for (JsonNode r : edge.path("relationships")) {
            if (r.path("kind").asText().equals("METHOD_CALL")) {
                call = r;
            }
        }
        assertThat(call).isNotNull();
        assertThat(call.path("samples").get(0).path("line").asInt()).isEqualTo(11);
        mvc.perform(get(base + "/edge?source=" + enc(C + "Service") + "&target=" + enc(C + "Controller")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());

        JsonNode path = getJson(base + "/path?from=" + enc(C + "Controller") + "&to=" + enc(C + "Repository"));
        assertThat(path.path("found").asBoolean()).isTrue();
        assertThat(ids(path.path("path"), "name")).containsExactly("Controller", "Service", "Repository");
        JsonNode noPath = getJson(base + "/path?from=" + enc(C + "Repository") + "&to=" + enc(C + "Controller"));
        assertThat(noPath.path("found").asBoolean()).isFalse();

        JsonNode search = getJson(base + "/nodes?q=serv");
        assertThat(search.path("items").get(0).path("entity").path("id").asText()).isEqualTo(C + "Service");
        JsonNode methodSearch = getJson(base + "/nodes?layer=METHOD&q=describe");
        assertThat(methodSearch.path("items").get(0).path("entity").path("id").asText()).isEqualTo(C + "Service#describe(long)");

        JsonNode neighborhood = getJson(base + "/graph?scope=NEIGHBORHOOD&depth=1&focus=" + enc(C + "Repository"));
        assertThat(ids(neighborhood.path("nodes"), "id")).containsExactly(C + "Repository", C + "Service");
        JsonNode dependentsScope = getJson(base + "/graph?scope=DEPENDENTS&focus=" + enc(C + "Repository"));
        assertThat(ids(dependentsScope.path("nodes"), "id")).containsExactly(C + "Repository", C + "Service", C + "Controller");
        JsonNode filtered = getJson(base + "/graph?relationshipKinds=OBJECT_CREATION");
        assertThat(edges(filtered)).containsExactly(C + "Service->" + C + "Repository");
        JsonNode limited = getJson(base + "/graph?limit=2");
        assertThat(limited.path("nodes")).hasSize(2);
        assertThat(limited.path("truncated").asBoolean()).isTrue();
        assertThat(limited.path("matchedNodes").asInt()).isEqualTo(3);
        assertThat(limited.path("truncationReason").asText()).contains("2 most connected of 3");
    }

    @Test
    void impactAnalysisMatchesGoldenValues() throws Exception {
        long chain = importFixture("chain")[0];
        MvcResult result = postJson("/api/projects/" + chain + "/impact-analysis", Map.of("nodeId", C + "Repository"));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode impact = read(result);
        assertThat(impact.path("eligibleCount").asInt()).isEqualTo(3);
        assertThat(impact.path("directCount").asInt()).isEqualTo(1);
        assertThat(impact.path("transitiveCount").asInt()).isEqualTo(2);
        assertThat(impact.path("maxDistance").asInt()).isEqualTo(2);
        assertThat(impact.path("score").asDouble()).isCloseTo(68.5, within(1e-9));
        assertThat(impact.path("scoreRounded").decimalValue()).isEqualByComparingTo("68.5");
        assertThat(impact.path("percentage").asDouble()).isCloseTo(100.0, within(1e-9));
        assertThat(impact.path("level").asText()).isEqualTo("HIGH");
        assertThat(impact.path("affectedFileCount").asInt()).isEqualTo(2);
        assertThat(ids(impact.path("affected"), null)).hasSize(2);
        assertThat(impact.path("affected").get(0).path("entity").path("name").asText()).isEqualTo("Service");
        assertThat(impact.path("affected").get(0).path("direct").asBoolean()).isTrue();
        assertThat(ids(impact.path("affected").get(1).path("path"), "name")).containsExactly("Controller", "Service", "Repository");
        assertThat(impact.path("disclaimer").asText()).contains("does not necessarily mean");
        assertThat(impact.path("factors")).hasSize(3);

        long diamond = importFixture("diamond")[0];
        JsonNode d = read(postJson("/api/projects/" + diamond + "/impact-analysis", Map.of("nodeId", "com.acme.diamond.D")));
        assertThat(d.path("score").asDouble()).isCloseTo(76.0, within(1e-9));
        assertThat(d.path("directCount").asInt()).isEqualTo(2);
        assertThat(d.path("transitiveCount").asInt()).isEqualTo(3);
        assertThat(ids(d.path("affected"), null)).hasSize(3);

        long isolated = importFixture("isolated")[0];
        JsonNode i = read(postJson("/api/projects/" + isolated + "/impact-analysis", Map.of("nodeId", "com.acme.iso.Isolated")));
        assertThat(i.path("score").asDouble()).isZero();
        assertThat(i.path("transitiveCount").asInt()).isZero();
        assertThat(i.path("level").asText()).isEqualTo("LOW");

        JsonNode method = read(postJson("/api/projects/" + chain + "/impact-analysis",
                Map.of("nodeId", C + "Repository#find(long)", "layer", "METHOD")));
        assertThat(method.path("eligibleCount").asInt()).isEqualTo(4);
        assertThat(ids(method.path("affected"), null)).hasSize(2);
        assertThat(method.path("affectedTypeCount").asInt()).isEqualTo(2);

        MvcResult wrongLayer = postJson("/api/projects/" + chain + "/impact-analysis", Map.of("nodeId", C + "Repository", "layer", "METHOD"));
        assertThat(wrongLayer.getResponse().getStatus()).isEqualTo(400);
        MvcResult unknown = postJson("/api/projects/" + chain + "/impact-analysis", Map.of("nodeId", "com.nope.Missing"));
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        MvcResult blank = postJson("/api/projects/" + chain + "/impact-analysis", Map.of("nodeId", ""));
        assertThat(blank.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(blank).path("fieldErrors").get(0).path("field").asText()).isEqualTo("nodeId");
    }

    @Test
    void cyclesEndpointReportsComponentsWithValidRepresentativeCycle() throws Exception {
        long projectId = importFixture("cycle")[0];
        JsonNode cycles = getJson("/api/projects/" + projectId + "/cycles");
        assertThat(cycles.path("componentCount").asInt()).isEqualTo(1);
        JsonNode component = cycles.path("components").get(0);
        assertThat(component.path("size").asInt()).isEqualTo(3);
        assertThat(ids(component.path("cycle"), "name")).containsExactly("A", "B", "C", "A");
        assertThat(component.path("steps")).hasSize(3);
        assertThat(ids(component.path("steps").get(0).path("kinds"), null)).containsExactly("FIELD_TYPE");
        assertThat(component.path("steps").get(0).path("firstReference").path("filePath").asText())
                .isEqualTo("cycle/src/main/java/com/acme/cycle/A.java");
        JsonNode region = getJson("/api/projects/" + projectId + "/graph?scope=CYCLE&component=0");
        assertThat(region.path("nodes")).hasSize(3);
        assertThat(edges(region)).hasSize(3);

        JsonNode impact = read(postJson("/api/projects/" + projectId + "/impact-analysis", Map.of("nodeId", "com.acme.cycle.A")));
        assertThat(impact.path("transitiveCount").asInt()).isEqualTo(2);

        long chain = importFixture("chain")[0];
        assertThat(getJson("/api/projects/" + chain + "/cycles").path("componentCount").asInt()).isZero();
        mvc.perform(get("/api/projects/" + chain + "/graph?scope=CYCLE&component=0"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }

    @Test
    void comparisonOfTwoVersionsMatchesTheExpectedChanges() throws Exception {
        long[] v1 = importFixture("versions/v1");
        long projectId = v1[0];
        MvcResult upload = uploadVersion(projectId, ZipFixtures.zip("versions/v2"), "second");
        assertThat(upload.getResponse().getStatus()).isEqualTo(201);
        long v2 = read(upload).path("analysis").path("id").asLong();
        assertThat(read(upload).path("version").path("versionNumber").asInt()).isEqualTo(2);
        assertThat(awaitAnalysis(projectId, v2).path("status").asText()).isEqualTo("COMPLETED");

        String p = "com.acme.ver.";
        JsonNode cmp = getJson("/api/projects/" + projectId + "/compare?base=" + v1[1] + "&target=" + v2);
        assertThat(ids(cmp.path("addedEntities"), "id")).containsExactly(p + "Delta");
        assertThat(ids(cmp.path("removedEntities"), "id")).containsExactly(p + "Legacy");
        assertThat(cmp.path("unchangedEntityCount").asInt()).isEqualTo(3);
        Set<String> added = new TreeSet<>();
        cmp.path("addedEdges").forEach(e -> added.add(e.path("source").asText() + "->" + e.path("target").asText()));
        Set<String> removed = new TreeSet<>();
        cmp.path("removedEdges").forEach(e -> removed.add(e.path("source").asText() + "->" + e.path("target").asText()));
        assertThat(added).containsExactly(p + "Alpha->" + p + "Gamma", p + "Delta->" + p + "Beta");
        assertThat(removed).containsExactly(p + "Alpha->" + p + "Beta", p + "Legacy->" + p + "Gamma");
        assertThat(cmp.path("unchangedEdgeCount").asInt()).isEqualTo(1);
        assertThat(cmp.path("addedRelationshipCount").asInt()).isEqualTo(4);
        assertThat(cmp.path("removedRelationshipCount").asInt()).isEqualTo(3);
        Map<String, JsonNode> metrics = new HashMap<>();
        cmp.path("metrics").forEach(m -> metrics.put(m.path("metric").asText(), m));
        assertThat(metrics.get("Types").path("delta").asLong()).isZero();
        assertThat(metrics.get("Type dependencies").path("base").asLong()).isEqualTo(3);
        assertThat(metrics.get("Type dependencies").path("target").asLong()).isEqualTo(3);

        JsonNode reverse = getJson("/api/projects/" + projectId + "/compare?base=" + v2 + "&target=" + v1[1]);
        assertThat(ids(reverse.path("addedEntities"), "id")).containsExactly(p + "Legacy");

        // historical requests are honoured, never replaced by the latest snapshot
        JsonNode oldGraph = getJson("/api/projects/" + projectId + "/graph?analysisId=" + v1[1]);
        assertThat(ids(oldGraph.path("nodes"), "id")).contains(p + "Legacy").doesNotContain(p + "Delta");
        JsonNode newGraph = getJson("/api/projects/" + projectId + "/graph");
        assertThat(ids(newGraph.path("nodes"), "id")).contains(p + "Delta").doesNotContain(p + "Legacy");
        JsonNode oldImpact = read(postJson("/api/projects/" + projectId + "/impact-analysis",
                Map.of("analysisId", v1[1], "nodeId", p + "Gamma")));
        assertThat(oldImpact.path("analysisId").asLong()).isEqualTo(v1[1]);
        assertThat(ids(oldImpact.path("affected"), null)).hasSize(3);
        JsonNode newImpact = read(postJson("/api/projects/" + projectId + "/impact-analysis", Map.of("nodeId", p + "Gamma")));
        assertThat(ids(newImpact.path("affected"), null)).hasSize(3);
        assertThat(newImpact.path("affected").toString()).contains("Delta").doesNotContain("Legacy");
    }

    @Test
    void snapshotsAreIsolatedBetweenProjects() throws Exception {
        long[] a = importFixture("chain");
        long[] b = importFixture("diamond");
        mvc.perform(get("/api/projects/" + a[0] + "/graph?analysisId=" + b[1]))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
        mvc.perform(get("/api/projects/" + a[0] + "/analyses/" + b[1]))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(get("/api/projects/" + a[0] + "/compare?base=" + a[1] + "&target=" + b[1]))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        MvcResult crossImpact = postJson("/api/projects/" + a[0] + "/impact-analysis",
                Map.of("analysisId", b[1], "nodeId", "com.acme.diamond.D"));
        assertThat(crossImpact.getResponse().getStatus()).isEqualTo(404);
        mvc.perform(get("/api/projects/" + a[0] + "/reports/summary.csv?analysisId=" + b[1]))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }

    @Test
    void partialSnapshotsExposeDiagnosticsAndFiles() throws Exception {
        long[] ids = importFixture("malformed");
        JsonNode analysis = getJson("/api/projects/" + ids[0] + "/analyses/" + ids[1]);
        assertThat(analysis.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(analysis.path("partialReasons").get(0).asText()).isEqualTo("1 file(s) could not be parsed");
        JsonNode errors = getJson("/api/projects/" + ids[0] + "/analyses/" + ids[1] + "/diagnostics?severity=ERROR");
        assertThat(errors.path("total").asLong()).isPositive();
        errors.path("items").forEach(d -> assertThat(d.path("category").asText()).isEqualTo("PARSE_ERROR"));
        JsonNode files = getJson("/api/projects/" + ids[0] + "/analyses/" + ids[1] + "/files");
        assertThat(files.path("total").asInt()).isEqualTo(3);
        List<String> failed = new ArrayList<>();
        files.path("files").forEach(f -> {
            if (f.path("status").asText().equals("FAILED")) {
                failed.add(f.path("path").asText());
            }
        });
        assertThat(failed).containsExactly("malformed/src/main/java/com/acme/bad/Broken.java");
        JsonNode impact = read(postJson("/api/projects/" + ids[0] + "/impact-analysis", Map.of("nodeId", "com.acme.bad.Helper")));
        assertThat(impact.path("analysisStatus").asText()).isEqualTo("PARTIAL");
        assertThat(impact.path("limitations").toString()).contains("partial");
        mvc.perform(get("/api/projects/" + ids[0] + "/analyses/" + ids[1] + "/diagnostics?size=0"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }

    @Test
    void dashboardAndSettingsReflectRealData() throws Exception {
        importFixture("diamond");
        JsonNode dashboard = getJson("/api/dashboard");
        assertThat(dashboard.path("totals").path("projects").asInt()).isPositive();
        assertThat(dashboard.path("totals").path("types").asLong()).isPositive();
        assertThat(dashboard.path("hotspots")).isNotEmpty();
        assertThat(dashboard.path("recent")).isNotEmpty();

        JsonNode settings = getJson("/api/settings");
        assertThat(settings.path("lowThreshold").decimalValue()).isEqualByComparingTo("30");
        MvcResult invalid = mvc.perform(withCsrf(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/settings")
                .contentType("application/json")
                .content("{\"lowThreshold\":70,\"highThreshold\":60,\"includeInferred\":true,\"explorerNodeLimit\":300}"))).andReturn();
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(invalid).path("fieldErrors").get(0).path("field").asText()).isEqualTo("highThreshold");
        MvcResult outOfRange = mvc.perform(withCsrf(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/settings")
                .contentType("application/json")
                .content("{\"lowThreshold\":0,\"highThreshold\":160,\"includeInferred\":true,\"explorerNodeLimit\":5}"))).andReturn();
        assertThat(outOfRange.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(outOfRange).path("fieldErrors")).hasSizeGreaterThanOrEqualTo(3);
    }
}
