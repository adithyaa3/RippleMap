package com.example.shop.domain;

import java.util.ArrayList;
import java.util.List;

public class Order {
    private final long id;
    private final Customer customer;
    private final List<OrderLine> lines = new ArrayList<>();
    private OrderStatus status = OrderStatus.NEW;

    public Order(long id, Customer customer) {
        this.id = id;
        this.customer = customer;
    }

    public void add(OrderLine line) {
        lines.add(line);
    }

    public Money total() {
        Money sum = Money.zero("EUR");
        for (OrderLine line : lines) {
            sum = sum.plus(line.total());
        }
        return sum;
    }

    public long id() {
        return id;
    }

    public Customer customer() {
        return customer;
    }

    public List<OrderLine> lines() {
        return lines;
    }

    public OrderStatus status() {
        return status;
    }

    public void markPaid() {
        status = OrderStatus.PAID;
    }
}
