package com.ripplemap.report;

import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Minimal builder for self-contained HTML reports. Every piece of text is HTML-escaped; reports contain no
 * scripts and declare a Content-Security-Policy that forbids loading anything.
 */
public final class HtmlReport {

    private static final String STYLE = """
            :root{color-scheme:light dark;--fg:#1d2433;--muted:#5b6577;--bg:#ffffff;--panel:#f5f7fa;--line:#dde2ea;--accent:#2f6fde;--high:#c2410c;--medium:#a16207;--low:#15803d}
            @media (prefers-color-scheme:dark){:root{--fg:#e6e9ef;--muted:#9aa4b5;--bg:#11151c;--panel:#181e27;--line:#2a3342;--accent:#6ea2ff;--high:#fb923c;--medium:#facc15;--low:#4ade80}}
            *{box-sizing:border-box}body{margin:0;font:14px/1.5 -apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;color:var(--fg);background:var(--bg)}
            main{max-width:1100px;margin:0 auto;padding:32px 24px 64px}h1{font-size:24px;margin:0 0 4px}h2{font-size:18px;margin:32px 0 12px;padding-bottom:6px;border-bottom:1px solid var(--line)}
            h3{font-size:15px;margin:20px 0 8px}.muted{color:var(--muted)}.meta{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;margin:16px 0}
            .card{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:12px 14px}.card b{display:block;font-size:20px}
            table{border-collapse:collapse;width:100%;margin:8px 0 16px;font-size:13px}th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:top}
            th{background:var(--panel);font-weight:600}td.num,th.num{text-align:right;font-variant-numeric:tabular-nums}code{font:12px ui-monospace,SFMono-Regular,Consolas,monospace;word-break:break-all}
            .badge{display:inline-block;padding:1px 8px;border-radius:999px;font-size:12px;font-weight:600;border:1px solid currentColor}.HIGH{color:var(--high)}.MEDIUM{color:var(--medium)}.LOW{color:var(--low)}
            .note{background:var(--panel);border-left:3px solid var(--accent);padding:10px 12px;margin:12px 0;border-radius:4px}ul{padding-left:20px}footer{margin-top:40px;font-size:12px;color:var(--muted)}
            """;

    private final StringBuilder body = new StringBuilder();
    private final String title;

    public HtmlReport(String title) {
        this.title = title;
    }

    public static String esc(Object value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value.toString(), StandardCharsets.UTF_8.name());
    }

    public HtmlReport heading(int level, String text) {
        body.append("<h").append(level).append('>').append(esc(text)).append("</h").append(level).append(">\n");
        return this;
    }

    public HtmlReport paragraph(String text, boolean muted) {
        body.append(muted ? "<p class=\"muted\">" : "<p>").append(esc(text)).append("</p>\n");
        return this;
    }

    public HtmlReport note(String text) {
        body.append("<div class=\"note\">").append(esc(text)).append("</div>\n");
        return this;
    }

    public HtmlReport cards(List<String[]> labelValuePairs) {
        body.append("<div class=\"meta\">");
        for (String[] pair : labelValuePairs) {
            body.append("<div class=\"card\"><span class=\"muted\">").append(esc(pair[0])).append("</span><b>")
                    .append(esc(pair[1])).append("</b></div>");
        }
        body.append("</div>\n");
        return this;
    }

    public HtmlReport list(List<String> items) {
        body.append("<ul>");
        for (String item : items) {
            body.append("<li>").append(esc(item)).append("</li>");
        }
        body.append("</ul>\n");
        return this;
    }

    /** A table; cells of type Number are right-aligned, cells wrapped by {@link #code(String)} use monospace. */
    public HtmlReport table(String[] headers, List<Object[]> rows) {
        body.append("<table><thead><tr>");
        for (String header : headers) {
            body.append("<th>").append(esc(header)).append("</th>");
        }
        body.append("</tr></thead><tbody>");
        for (Object[] row : rows) {
            body.append("<tr>");
            for (Object cell : row) {
                if (cell instanceof Number) {
                    body.append("<td class=\"num\">").append(esc(cell)).append("</td>");
                } else if (cell instanceof Code c) {
                    body.append("<td><code>").append(esc(c.text())).append("</code></td>");
                } else if (cell instanceof Badge b) {
                    body.append("<td><span class=\"badge ").append(esc(b.level())).append("\">").append(esc(b.level()))
                            .append("</span></td>");
                } else {
                    body.append("<td>").append(esc(cell)).append("</td>");
                }
            }
            body.append("</tr>");
        }
        if (rows.isEmpty()) {
            body.append("<tr><td colspan=\"").append(headers.length).append("\" class=\"muted\">None</td></tr>");
        }
        body.append("</tbody></table>\n");
        return this;
    }

    public record Code(String text) {
    }

    public record Badge(String level) {
    }

    public static Code code(String text) {
        return new Code(text);
    }

    public static Badge badge(String level) {
        return new Badge(level);
    }

    public byte[] render(String footer) {
        String html = "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">"
                + "<title>" + esc(title) + "</title><style>" + STYLE + "</style></head><body><main>\n"
                + body + "<footer>" + esc(footer) + "</footer></main></body></html>\n";
        return html.getBytes(StandardCharsets.UTF_8);
    }
}
