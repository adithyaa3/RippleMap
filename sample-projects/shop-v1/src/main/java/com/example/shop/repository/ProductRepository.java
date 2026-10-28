package com.example.shop.repository;

import com.example.shop.domain.Product;
import java.util.Optional;

public interface ProductRepository {
    Optional<Product> findBySku(String sku);
}
