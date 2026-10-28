package com.ripplemap.report;

import java.nio.charset.StandardCharsets;

/**
 * RFC 4180 CSV writer. Text cells that a spreadsheet could interpret as a formula (starting with
 * {@code = + - @}, tab or carriage return) are prefixed with an apostrophe; numeric cells are written as numbers.
 */
public final class CsvWriter {

    private final StringBuilder out = new StringBuilder();
    private boolean rowStarted;

    public CsvWriter row(Object... cells) {
        for (Object cell : cells) {
            cell(cell);
        }
        return endRow();
    }

    public CsvWriter cell(Object value) {
        if (rowStarted) {
            out.append(',');
        }
        rowStarted = true;
        if (value == null) {
            return this;
        }
        if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
            return this;
        }
        out.append(quote(protect(value.toString())));
        return this;
    }

    public CsvWriter endRow() {
        out.append("\r\n");
        rowStarted = false;
        return this;
    }

    public byte[] toBytes() {
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return out.toString();
    }

    static String protect(String text) {
        if (!text.isEmpty()) {
            char first = text.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                    || first == '＝' || first == '＋' || first == '－' || first == '＠') {
                return "'" + text;
            }
        }
        return text;
    }

    static String quote(String text) {
        boolean needs = text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0 || text.startsWith(" ") || text.endsWith(" ");
        if (!needs) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
