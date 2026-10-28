package com.example.shop.pricing;

import com.example.shop.domain.Money;

public interface DiscountPolicy {
    Money apply(Money total);
}
