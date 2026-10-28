package com.acme.bad;

public class Good {
    private Helper helper;
    private Broken broken;

    public String run() {
        return helper.help();
    }
}
