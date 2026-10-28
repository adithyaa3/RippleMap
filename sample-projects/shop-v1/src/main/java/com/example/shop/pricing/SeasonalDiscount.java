package com.example.shop.pricing;

import com.example.shop.domain.Money;
import java.math.BigDecimal;

public class SeasonalDiscount implements DiscountPolicy {
    @Override
    public Money apply(Money total) {
        return new Money(total.amount().multiply(new BigDecimal("0.9")), total.currency());
    }
}
