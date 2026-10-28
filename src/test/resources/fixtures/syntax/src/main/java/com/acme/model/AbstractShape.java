package com.acme.model;

public abstract class AbstractShape implements Shape {
    protected final String name;

    protected AbstractShape(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }
}
