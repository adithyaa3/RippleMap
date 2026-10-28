package com.example.shop.pricing;

import com.example.shop.domain.Money;
import com.example.shop.domain.Order;

public class PricingCalculator {
    private final DiscountPolicy discount = new SeasonalDiscount();

    public Money price(Order order) {
        return discount.apply(order.total());
    }
}
