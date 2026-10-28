package com.ripplemap.service;

import com.ripplemap.exception.ApiException;
import com.ripplemap.util.TextSanitizer;

import java.util.regex.Pattern;

/** Validation and normalization of user-supplied project metadata. */
public final class InputValidation {

    public static final int MAX_NAME = 100;
    public static final int MAX_DESCRIPTION = 2000;
    public static final int MAX_LABEL = 100;
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} ._()\\[\\]-]+");

    private InputValidation() {
    }

    public static String projectName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            throw ApiException.validation("name", "Enter a project name");
        }
        if (name.length() > MAX_NAME) {
            throw ApiException.validation("name", "The name must not exceed " + MAX_NAME + " characters");
        }
        if (!NAME.matcher(name).matches()) {
            throw ApiException.validation("name", "Use letters, digits, spaces and . _ - ( ) [ ] only");
        }
        return name;
    }

    /** Keeps line breaks and tabs, removes other control characters; blank becomes null. */
    public static String description(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\n' || c == '\t' || (!Character.isISOControl(c) && Character.getType(c) != Character.FORMAT)) {
                sb.append(c);
            }
        }
        String description = sb.toString().strip();
        if (description.length() > MAX_DESCRIPTION) {
            throw ApiException.validation("description",
                    "The description must not exceed " + MAX_DESCRIPTION + " characters");
        }
        return description.isEmpty() ? null : description;
    }

    public static String label(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String label = TextSanitizer.singleLine(raw, MAX_LABEL + 1);
        if (label.length() > MAX_LABEL) {
            throw ApiException.validation("label", "The label must not exceed " + MAX_LABEL + " characters");
        }
        return label;
    }

    /** Display-only original file name: the last path segment, single line, bounded. */
    public static String fileName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return TextSanitizer.singleLine(name, 200);
    }
}
