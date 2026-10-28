package com.example.shop.domain;

public class Product {
    private final String sku;
    private final String name;
    private final Money price;

    public Product(String sku, String name, Money price) {
        this.sku = sku;
        this.name = name;
        this.price = price;
    }

    public String sku() {
        return sku;
    }

    public String name() {
        return name;
    }

    public Money price() {
        return price;
    }
}
