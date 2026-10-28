package com.example.shop.pricing;

import com.example.shop.domain.Money;
import com.example.shop.domain.Order;

public class PricingCalculator {
    private final DiscountPolicy discount;

    public PricingCalculator(DiscountPolicy discount) {
        this.discount = discount;
    }

    public Money price(Order order) {
        return discount.apply(order.total());
    }
}
