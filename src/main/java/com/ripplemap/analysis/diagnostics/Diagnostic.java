package com.ripplemap.analysis.diagnostics;

/**
 * A single analysis diagnostic. All text fields are bounded and stripped of control
 * characters; they still originate from untrusted source code and must be escaped on output.
 *
 * @param entityId stable id of the declaration in which the problem occurred, if known
 * @param symbol   the name that could not be resolved, if applicable
 */
public record Diagnostic(
        Severity severity,
        DiagnosticCategory category,
        String filePath,
        int line,
        String entityId,
        String symbol,
        String message) {
}
