package com.example.shop.service;

import com.example.shop.domain.Product;

/** Deliberately forms a circular dependency with InventoryService. */
public class ReservationService {
    private final InventoryService inventory;

    public ReservationService(InventoryService inventory) {
        this.inventory = inventory;
    }

    public void hold(Product product, int quantity) {
        if (!inventory.available(product, quantity)) {
            throw new IllegalStateException("not enough stock for " + product.sku());
        }
    }
}
