package com.ripplemap.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.ripplemap.testsupport.IntegrationTestBase;
import com.ripplemap.testsupport.RawZipBuilder;
import com.ripplemap.testsupport.ZipFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Downloads every report type and inspects the actual content. */
class ReportIntegrationTest extends IntegrationTestBase {

    private MockHttpServletResponse download(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andReturn();
        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getStatus()).as(url).isEqualTo(200);
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment;").contains("filename=");
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo(
                "default-src 'none'; style-src 'unsafe-inline'; img-src data:; frame-ancestors 'none'");
        return response;
    }

    private static String text(MockHttpServletResponse response) {
        return new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void catalogAndAllSnapshotReportsUseTheSelectedSnapshot() throws Exception {
        long[] v1 = importFixture("versions/v1");
        long projectId = v1[0];
        long v2 = read(uploadVersion(projectId, ZipFixtures.zip("versions/v2"), null)).path("analysis").path("id").asLong();
        awaitAnalysis(projectId, v2);
        String base = "/api/projects/" + projectId + "/reports";

        JsonNode catalog = getJson(base + "?analysisId=" + v1[1]);
        assertThat(catalog.path("analysisId").asLong()).isEqualTo(v1[1]);
        assertThat(catalog.path("reports")).hasSize(7);
        for (JsonNode report : catalog.path("reports")) {
            assertThat(report.path("href").asText()).endsWith("analysisId=" + v1[1]);
            download(report.path("href").asText());
        }

        MockHttpServletResponse summary = download(base + "/summary.csv?analysisId=" + v1[1]);
        assertThat(summary.getContentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(summary.getHeader("Content-Disposition")).contains("-s1-summary.csv");
        String summaryText = text(summary);
        assertThat(summaryText).startsWith("section,metric,value\r\n");
        assertThat(summaryText).contains("Snapshot,Analysis id," + v1[1]).contains("Declarations,Types,4")
                .contains("Dependency graph,Type dependencies (distinct source/target pairs),3");

        String entitiesV1 = text(download(base + "/entities.csv?analysisId=" + v1[1]));
        String entitiesV2 = text(download(base + "/entities.csv?analysisId=" + v2));
        assertThat(entitiesV1).contains("com.acme.ver.Legacy").doesNotContain("com.acme.ver.Delta");
        assertThat(entitiesV2).contains("com.acme.ver.Delta").doesNotContain("com.acme.ver.Legacy");
        assertThat(entitiesV1.lines().findFirst().orElseThrow()).startsWith("layer,id,name,kind");

        String deps = text(download(base + "/dependencies.csv?analysisId=" + v1[1]));
        assertThat(deps).contains("TYPE,com.acme.ver.Alpha,Alpha,com.acme.ver.Beta,Beta,FIELD_TYPE,RESOLVED,1,1,0,");

        MockHttpServletResponse html = download(base + "/analysis.html?analysisId=" + v1[1]);
        assertThat(html.getContentType()).isEqualTo("text/html;charset=UTF-8");
        String htmlText = text(html);
        assertThat(htmlText).startsWith("<!DOCTYPE html>").contains("Snapshot #1").contains("Known limitations")
                .doesNotContain("<script");

        MockHttpServletResponse jsonReport = download(base + "/analysis.json?analysisId=" + v2);
        assertThat(jsonReport.getContentType()).isEqualTo("application/json;charset=UTF-8");
        JsonNode doc = json.readTree(jsonReport.getContentAsByteArray());
        assertThat(doc.path("analysis").path("id").asLong()).isEqualTo(v2);
        assertThat(doc.path("relationships").findValuesAsText("sourceId")).contains("com.acme.ver.Delta");
        assertThat(doc.path("entities").findValuesAsText("id")).doesNotContain("com.acme.ver.Legacy");

        String cmpCsv = text(download(base + "/comparison.csv?base=" + v1[1] + "&target=" + v2));
        assertThat(cmpCsv).contains("added,entity,com.acme.ver.Delta").contains("removed,entity,com.acme.ver.Legacy");
        String cmpHtml = text(download(base + "/comparison.html?base=" + v1[1] + "&target=" + v2));
        assertThat(cmpHtml).contains("Added entities").contains("Legacy");
    }

    @Test
    void impactReportsMatchTheImpactEndpoint() throws Exception {
        long projectId = importFixture("chain")[0];
        String node = URLEncoder.encode("com.acme.chain.Repository", StandardCharsets.UTF_8);
        String base = "/api/projects/" + projectId + "/reports/impact";
        String csv = text(download(base + ".csv?nodeId=" + node));
        List<String> lines = csv.lines().toList();
        assertThat(lines.get(1)).contains(",3,1,2,2,68.5,100.0,HIGH");
        assertThat(csv).contains("com.acme.chain.Controller,Controller,CLASS").contains("Controller -> Service -> Repository");
        String html = text(download(base + ".html?nodeId=" + node));
        assertThat(html).contains("68.5").contains("HIGH").contains("does not necessarily mean");
        JsonNode doc = json.readTree(download(base + ".json?nodeId=" + node).getContentAsByteArray());
        assertThat(doc.path("impact").path("score").asDouble()).isEqualTo(68.5);
        assertThat(mvc.perform(get(base + ".csv?nodeId=missing")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get(base + ".xml?nodeId=" + node)).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void untrustedTextIsEscapedInHtmlAndNeutralizedInCsv() throws Exception {
        byte[] zip = new RawZipBuilder()
                .add("p/src/Evil.java", "package p; class Evil { String s = \"</script><script>alert(1)</script>\"; void m( <img src=x onerror=alert(2)> }")
                .add("p/src/Audited.java", "package p; public @interface Audited { }")
                .add("p/src/User.java", "package p; @Audited public class User { }")
                .build();
        MvcResult imported = importZip(unique("Hostile"), zip, false);
        long projectId = read(imported).path("project").path("id").asLong();
        awaitAnalysis(projectId, read(imported).path("analysis").path("id").asLong());
        String base = "/api/projects/" + projectId + "/reports";
        String html = text(download(base + "/analysis.html"));
        assertThat(html).doesNotContain("<script").doesNotContain("<img").doesNotContain("onerror=alert");
        String diagnostics = text(download(base + "/diagnostics.csv"));
        assertThat(diagnostics).contains("PARSE_ERROR");
        String deps = text(download(base + "/dependencies.csv"));
        assertThat(deps).contains(",'@Audited,").doesNotContain(",@Audited,");
    }
}
