package com.acme.ver;

public class Delta {
    private Beta beta;

    public int value() {
        return beta.compute() + 1;
    }
}
