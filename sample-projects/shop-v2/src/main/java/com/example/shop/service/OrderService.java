package com.example.shop.service;

import com.example.shop.domain.Order;

public interface OrderService {
    Order place(long customerId, String sku, int quantity);

    Order find(long orderId);
}
