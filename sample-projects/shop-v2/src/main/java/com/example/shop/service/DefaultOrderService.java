package com.example.shop.service;

import com.example.shop.domain.Customer;
import com.example.shop.domain.Order;
import com.example.shop.domain.OrderLine;
import com.example.shop.domain.Product;
import com.example.shop.events.EventPublisher;
import com.example.shop.events.OrderPlaced;
import com.example.shop.payment.PaymentGateway;
import com.example.shop.pricing.NoDiscount;
import com.example.shop.pricing.PricingCalculator;
import com.example.shop.repository.OrderRepository;
import com.example.shop.support.Auditable;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Auditable("orders")
public class DefaultOrderService implements OrderService {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultOrderService.class);
    private final AtomicLong ids = new AtomicLong();
    private final OrderRepository orders;
    private final CustomerService customers;
    private final InventoryService inventory;
    private final PaymentGateway payments;
    private final PricingCalculator pricing = new PricingCalculator(new NoDiscount());
    private final EventPublisher events;

    public DefaultOrderService(OrderRepository orders, CustomerService customers, InventoryService inventory,
                               PaymentGateway payments, EventPublisher events) {
        this.orders = orders;
        this.customers = customers;
        this.inventory = inventory;
        this.payments = payments;
        this.events = events;
    }

    @Override
    public Order place(long customerId, String sku, int quantity) {
        Customer customer = customers.require(customerId);
        Product product = inventory.reserve(sku, quantity);
        Order order = new Order(ids.incrementAndGet(), customer);
        order.add(new OrderLine(product, quantity));
        if (payments.charge(customer.email(), pricing.price(order))) {
            order.markPaid();
        }
        LOG.info("placed order {}", order.id());
        events.publish(new OrderPlaced(order.id(), order.total()));
        return orders.save(order);
    }

    @Override
    public Order find(long orderId) {
        return orders.findById(orderId).orElseThrow();
    }
}
