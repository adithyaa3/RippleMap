package com.example.shop.web;

import com.example.shop.domain.Order;

public record OrderDto(long id, String status, String total) {
    public static OrderDto from(Order order) {
        return new OrderDto(order.id(), order.status().name(), order.total().amount().toPlainString());
    }
}
