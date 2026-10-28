package com.acme.chain;

public class Service {
    private final Repository repository = new Repository();

    public String describe(long id) {
        return "item-" + repository.find(id);
    }
}
