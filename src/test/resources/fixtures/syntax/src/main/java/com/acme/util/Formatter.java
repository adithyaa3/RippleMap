package com.acme.util;

public final class Formatter {

    private Formatter() {
    }

    public static String format(double value) {
        return String.valueOf(value);
    }

    public static String format(double value, int digits) {
        return String.format("%." + digits + "f", value);
    }

    public static String format(String text) {
        return text.strip();
    }

    public static class Options {
        int digits = 2;
    }
}
