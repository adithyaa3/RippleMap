package com.example.shop.events;

import com.example.shop.domain.Money;

public record OrderPlaced(long orderId, Money total) {
}
