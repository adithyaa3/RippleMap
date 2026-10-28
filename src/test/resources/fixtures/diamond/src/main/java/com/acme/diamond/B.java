package com.acme.diamond;

public class B {
    private final D d = new D();

    public int value() {
        return d.base() + 1;
    }
}
