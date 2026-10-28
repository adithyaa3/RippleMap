package com.ripplemap.report;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterTest {

    @Test
    void quotesSeparatorsQuotesAndLineBreaks() {
        String csv = new CsvWriter().row("plain", "a,b", "say \"hi\"", "two\nlines", " padded").toString();
        assertThat(csv).isEqualTo("plain,\"a,b\",\"say \"\"hi\"\"\",\"two\nlines\",\" padded\"\r\n");
    }

    @Test
    void neutralizesSpreadsheetFormulasInTextCells() {
        String csv = new CsvWriter().row("=1+1", "+cmd", "-2+3", "@SUM(A1)", "\tx", "＝1").toString();
        assertThat(csv).isEqualTo("'=1+1,'+cmd,'-2+3,'@SUM(A1),'\tx,'＝1\r\n");
    }

    @Test
    void numbersAndBooleansAreWrittenAsValues() {
        String csv = new CsvWriter().row(-5, 3.5, true, null, "").toString();
        assertThat(csv).isEqualTo("-5,3.5,true,,\r\n");
    }

    @Test
    void outputIsUtf8() {
        byte[] bytes = new CsvWriter().row("Größe", "日本").toBytes();
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("Größe,日本\r\n");
    }

    @Test
    void htmlReportEscapesEverything() {
        HtmlReport report = new HtmlReport("<title>");
        report.heading(1, "<script>alert(1)</script>").paragraph("a & b \"q\" 'x'", false)
                .table(new String[]{"<th>"}, java.util.List.<Object[]>of(new Object[]{"<td onclick=x>"}))
                .list(java.util.List.of("<li>"));
        String html = new String(report.render("<footer>"), StandardCharsets.UTF_8);
        assertThat(html).doesNotContain("<script>alert").doesNotContain("<td onclick").doesNotContain("<title><title>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;").contains("a &amp; b &quot;q&quot; &#39;x&#39;");
        assertThat(html).contains("Content-Security-Policy").contains("default-src 'none'");
        assertThat(html).doesNotContain("<script");
    }
}
