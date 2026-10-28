package com.example.shop.service;

import com.example.shop.domain.Order;
import com.example.shop.repository.InMemoryOrderRepository;

class DefaultOrderServiceTest {
    void findsSavedOrders() {
        InMemoryOrderRepository repository = new InMemoryOrderRepository();
        Order order = repository.save(new Order(1, null));
        assert repository.findById(order.id()).isPresent();
    }
}
