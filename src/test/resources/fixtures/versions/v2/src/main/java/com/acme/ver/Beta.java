package com.acme.ver;

public class Beta {
    private final Gamma gamma = new Gamma();

    public int compute() {
        return gamma.base();
    }
}
