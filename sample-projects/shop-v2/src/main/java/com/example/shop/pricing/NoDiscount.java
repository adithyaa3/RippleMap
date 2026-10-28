package com.example.shop.pricing;

import com.example.shop.domain.Money;

public class NoDiscount implements DiscountPolicy {
    @Override
    public Money apply(Money total) {
        return total;
    }
}
