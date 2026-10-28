package com.example.shop.repository;

import com.example.shop.domain.Customer;
import java.util.Optional;

public interface CustomerRepository {
    Optional<Customer> findById(long id);
}
