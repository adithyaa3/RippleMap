package com.example.shop.service;

import com.example.shop.domain.Product;
import java.util.HashMap;
import java.util.Map;

public class ReservationService {
    private final Map<String, Integer> held = new HashMap<>();

    public void hold(Product product, int quantity) {
        held.merge(product.sku(), quantity, Integer::sum);
    }
}
