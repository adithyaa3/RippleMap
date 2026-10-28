package com.acme.diamond;

public class A {
    private B b;
    private C c;

    public int run() {
        return b.value() + c.value();
    }
}
