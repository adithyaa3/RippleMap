package com.acme.iso;

public class Isolated {
    private final java.util.List<String> names = new java.util.ArrayList<>();

    public int count() {
        return names.size();
    }
}
