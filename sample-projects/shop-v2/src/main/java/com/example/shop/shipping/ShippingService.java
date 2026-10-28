package com.example.shop.shipping;

import com.example.shop.domain.Order;
import com.example.shop.domain.OrderStatus;

public class ShippingService {
    public boolean canShip(Order order) {
        return order.status() == OrderStatus.PAID && !order.lines().isEmpty();
    }
}
