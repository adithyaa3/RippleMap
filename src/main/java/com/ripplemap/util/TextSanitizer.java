package com.ripplemap.util;

/** Normalizes untrusted text for safe storage and display (escaping is still required on output). */
public final class TextSanitizer {

    private TextSanitizer() {
    }

    /**
     * Collapses whitespace and line breaks into single spaces, removes other control characters
     * and truncates to {@code maxLength} characters (appending an ellipsis when truncated).
     */
    public static String singleLine(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(Math.min(text.length(), maxLength + 1));
        boolean lastWasSpace = false;
        for (int i = 0; i < text.length() && sb.length() <= maxLength; i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '\u0085' || c == ' ' || c == ' ') {
                if (!lastWasSpace && sb.length() > 0) {
                    sb.append(' ');
                    lastWasSpace = true;
                }
            } else if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) {
                // drop control and invisible formatting characters (e.g. bidi overrides)
            } else {
                sb.append(c);
                lastWasSpace = false;
            }
        }
        String result = sb.toString().strip();
        if (result.length() > maxLength) {
            return result.substring(0, Math.max(0, maxLength - 1)) + "…";
        }
        return result;
    }
}
