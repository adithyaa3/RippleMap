package com.acme.chain;

public class Controller {
    private final Service service;

    public Controller(Service service) {
        this.service = service;
    }

    public String get(long id) {
        return service.describe(id);
    }
}
