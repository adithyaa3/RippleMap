package com.example.shop.service;

import com.example.shop.domain.Product;
import com.example.shop.repository.ProductRepository;

public class InventoryService {
    private final ProductRepository products;
    private ReservationService reservations;

    public InventoryService(ProductRepository products) {
        this.products = products;
    }

    public void setReservations(ReservationService reservations) {
        this.reservations = reservations;
    }

    public Product reserve(String sku, int quantity) {
        Product product = products.findBySku(sku).orElseThrow();
        reservations.hold(product, quantity);
        return product;
    }

}
