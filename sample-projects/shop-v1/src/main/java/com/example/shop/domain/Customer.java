package com.example.shop.domain;

public class Customer {
    private final long id;
    private final String email;

    public Customer(long id, String email) {
        this.id = id;
        this.email = email;
    }

    public long id() {
        return id;
    }

    public String email() {
        return email;
    }
}
