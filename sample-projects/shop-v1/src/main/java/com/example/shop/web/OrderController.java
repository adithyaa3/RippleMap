package com.example.shop.web;

import com.example.shop.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {
    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/orders/{id}")
    public OrderDto get(@PathVariable long id) {
        return OrderDto.from(orders.find(id));
    }

    public OrderDto create(long customerId, String sku, int quantity) {
        return OrderDto.from(orders.place(customerId, sku, quantity));
    }
}
