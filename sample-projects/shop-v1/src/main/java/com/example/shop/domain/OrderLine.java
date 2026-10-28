package com.example.shop.domain;

public record OrderLine(Product product, int quantity) {
    public Money total() {
        Money sum = Money.zero(product.price().currency());
        for (int i = 0; i < quantity; i++) {
            sum = sum.plus(product.price());
        }
        return sum;
    }
}
