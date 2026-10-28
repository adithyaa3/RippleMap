package com.example.shop.service;

import com.example.shop.domain.Customer;
import com.example.shop.repository.CustomerRepository;

public class CustomerService {
    private final CustomerRepository customers;

    public CustomerService(CustomerRepository customers) {
        this.customers = customers;
    }

    public Customer require(long id) {
        return customers.findById(id).orElseThrow(() -> new IllegalArgumentException("unknown customer " + id));
    }
}
