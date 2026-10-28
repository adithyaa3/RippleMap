package com.example.shop.payment;

import com.example.shop.domain.Money;

public interface PaymentGateway {
    boolean charge(String customerEmail, Money amount);
}
