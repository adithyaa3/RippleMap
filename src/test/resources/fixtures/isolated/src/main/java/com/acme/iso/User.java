package com.acme.iso;

public class User {
    private final Helper helper = new Helper();

    public String name() {
        return helper.normalize("x");
    }
}
