"""Writes the sample 'shop' project (two versions) and packs each version as a ZIP.

Usage:  python make_samples.py        (run from the sample-projects directory)
"""
import os
import zipfile

BASE = 'src/main/java/com/example/shop/'
TEST = 'src/test/java/com/example/shop/'

COMMON = {
    BASE + 'domain/OrderStatus.java': '''package com.example.shop.domain;

public enum OrderStatus {
    NEW, PAID, SHIPPED, CANCELLED;

    public boolean isOpen() {
        return this == NEW || this == PAID;
    }
}
''',
    BASE + 'domain/Money.java': '''package com.example.shop.domain;

import java.math.BigDecimal;

public record Money(BigDecimal amount, String currency) {
    public Money {
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("negative amount");
        }
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount()), currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }
}
''',
    BASE + 'domain/OrderLine.java': '''package com.example.shop.domain;

public record OrderLine(Product product, int quantity) {
    public Money total() {
        Money sum = Money.zero(product.price().currency());
        for (int i = 0; i < quantity; i++) {
            sum = sum.plus(product.price());
        }
        return sum;
    }
}
''',
    BASE + 'domain/Product.java': '''package com.example.shop.domain;

public class Product {
    private final String sku;
    private final String name;
    private final Money price;

    public Product(String sku, String name, Money price) {
        this.sku = sku;
        this.name = name;
        this.price = price;
    }

    public String sku() {
        return sku;
    }

    public String name() {
        return name;
    }

    public Money price() {
        return price;
    }
}
''',
    BASE + 'domain/Customer.java': '''package com.example.shop.domain;

public class Customer {
    private final long id;
    private final String email;

    public Customer(long id, String email) {
        this.id = id;
        this.email = email;
    }

    public long id() {
        return id;
    }

    public String email() {
        return email;
    }
}
''',
    BASE + 'domain/Order.java': '''package com.example.shop.domain;

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
''',
    BASE + 'repository/OrderRepository.java': '''package com.example.shop.repository;

import com.example.shop.domain.Order;
import java.util.Optional;

public interface OrderRepository {
    Order save(Order order);

    Optional<Order> findById(long id);
}
''',
    BASE + 'repository/InMemoryOrderRepository.java': '''package com.example.shop.repository;

import com.example.shop.domain.Order;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class InMemoryOrderRepository implements OrderRepository {
    private final Map<Long, Order> orders = new HashMap<>();

    @Override
    public Order save(Order order) {
        orders.put(order.id(), order);
        return order;
    }

    @Override
    public Optional<Order> findById(long id) {
        return Optional.ofNullable(orders.get(id));
    }
}
''',
    BASE + 'repository/CustomerRepository.java': '''package com.example.shop.repository;

import com.example.shop.domain.Customer;
import java.util.Optional;

public interface CustomerRepository {
    Optional<Customer> findById(long id);
}
''',
    BASE + 'repository/ProductRepository.java': '''package com.example.shop.repository;

import com.example.shop.domain.Product;
import java.util.Optional;

public interface ProductRepository {
    Optional<Product> findBySku(String sku);
}
''',
    BASE + 'events/OrderPlaced.java': '''package com.example.shop.events;

import com.example.shop.domain.Money;

public record OrderPlaced(long orderId, Money total) {
}
''',
    BASE + 'events/EventPublisher.java': '''package com.example.shop.events;

public interface EventPublisher {
    void publish(Object event);
}
''',
    BASE + 'support/Auditable.java': '''package com.example.shop.support;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {
    String value() default "";
}
''',
    BASE + 'payment/PaymentGateway.java': '''package com.example.shop.payment;

import com.example.shop.domain.Money;

public interface PaymentGateway {
    boolean charge(String customerEmail, Money amount);
}
''',
    BASE + 'payment/CardPaymentGateway.java': '''package com.example.shop.payment;

import com.example.shop.domain.Money;
import com.thirdparty.payments.ChargeRequest;
import com.thirdparty.payments.PaymentsClient;

/** Adapter to a payment provider library that is not part of this project. */
public class CardPaymentGateway implements PaymentGateway {
    private final PaymentsClient client;

    public CardPaymentGateway(PaymentsClient client) {
        this.client = client;
    }

    @Override
    public boolean charge(String customerEmail, Money amount) {
        ChargeRequest request = ChargeRequest.of(customerEmail, amount.amount());
        return client.submit(request).isApproved();
    }
}
''',
    BASE + 'service/CustomerService.java': '''package com.example.shop.service;

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
''',
    BASE + 'service/OrderService.java': '''package com.example.shop.service;

import com.example.shop.domain.Order;

public interface OrderService {
    Order place(long customerId, String sku, int quantity);

    Order find(long orderId);
}
''',
}

V1 = {
    BASE + 'pricing/DiscountPolicy.java': '''package com.example.shop.pricing;

import com.example.shop.domain.Money;

public interface DiscountPolicy {
    Money apply(Money total);
}
''',
    BASE + 'pricing/SeasonalDiscount.java': '''package com.example.shop.pricing;

import com.example.shop.domain.Money;
import java.math.BigDecimal;

public class SeasonalDiscount implements DiscountPolicy {
    @Override
    public Money apply(Money total) {
        return new Money(total.amount().multiply(new BigDecimal("0.9")), total.currency());
    }
}
''',
    BASE + 'pricing/PricingCalculator.java': '''package com.example.shop.pricing;

import com.example.shop.domain.Money;
import com.example.shop.domain.Order;

public class PricingCalculator {
    private final DiscountPolicy discount = new SeasonalDiscount();

    public Money price(Order order) {
        return discount.apply(order.total());
    }
}
''',
    BASE + 'service/InventoryService.java': '''package com.example.shop.service;

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

    public boolean available(Product product, int quantity) {
        return quantity <= 100;
    }
}
''',
    BASE + 'service/ReservationService.java': '''package com.example.shop.service;

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
''',
    BASE + 'service/DefaultOrderService.java': '''package com.example.shop.service;

import com.example.shop.domain.Customer;
import com.example.shop.domain.Order;
import com.example.shop.domain.OrderLine;
import com.example.shop.domain.Product;
import com.example.shop.events.EventPublisher;
import com.example.shop.events.OrderPlaced;
import com.example.shop.payment.PaymentGateway;
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
    private final PricingCalculator pricing = new PricingCalculator();
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
''',
    BASE + 'web/OrderDto.java': '''package com.example.shop.web;

import com.example.shop.domain.Order;

public record OrderDto(long id, String status, String total) {
    public static OrderDto from(Order order) {
        return new OrderDto(order.id(), order.status().name(), order.total().amount().toPlainString());
    }
}
''',
    BASE + 'web/OrderController.java': '''package com.example.shop.web;

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
''',
    TEST + 'service/DefaultOrderServiceTest.java': '''package com.example.shop.service;

import com.example.shop.domain.Order;
import com.example.shop.repository.InMemoryOrderRepository;

class DefaultOrderServiceTest {
    void findsSavedOrders() {
        InMemoryOrderRepository repository = new InMemoryOrderRepository();
        Order order = repository.save(new Order(1, null));
        assert repository.findById(order.id()).isPresent();
    }
}
''',
}

V2 = dict(V1)
del V2[BASE + 'pricing/SeasonalDiscount.java']
V2[BASE + 'pricing/PricingCalculator.java'] = '''package com.example.shop.pricing;

import com.example.shop.domain.Money;
import com.example.shop.domain.Order;

public class PricingCalculator {
    private final DiscountPolicy discount;

    public PricingCalculator(DiscountPolicy discount) {
        this.discount = discount;
    }

    public Money price(Order order) {
        return discount.apply(order.total());
    }
}
'''
V2[BASE + 'pricing/NoDiscount.java'] = '''package com.example.shop.pricing;

import com.example.shop.domain.Money;

public class NoDiscount implements DiscountPolicy {
    @Override
    public Money apply(Money total) {
        return total;
    }
}
'''
# the circular dependency is removed: reservations no longer call back into the inventory
V2[BASE + 'service/ReservationService.java'] = '''package com.example.shop.service;

import com.example.shop.domain.Product;
import java.util.HashMap;
import java.util.Map;

public class ReservationService {
    private final Map<String, Integer> held = new HashMap<>();

    public void hold(Product product, int quantity) {
        held.merge(product.sku(), quantity, Integer::sum);
    }
}
'''
V2[BASE + 'service/InventoryService.java'] = V1[BASE + 'service/InventoryService.java'].replace(
    '''    public boolean available(Product product, int quantity) {
        return quantity <= 100;
    }
''', '')
V2[BASE + 'shipping/ShippingService.java'] = '''package com.example.shop.shipping;

import com.example.shop.domain.Order;
import com.example.shop.domain.OrderStatus;

public class ShippingService {
    public boolean canShip(Order order) {
        return order.status() == OrderStatus.PAID && !order.lines().isEmpty();
    }
}
'''
V2[BASE + 'service/DefaultOrderService.java'] = V1[BASE + 'service/DefaultOrderService.java'].replace(
    '    private final PricingCalculator pricing = new PricingCalculator();',
    '    private final PricingCalculator pricing = new PricingCalculator(new NoDiscount());').replace(
    'import com.example.shop.pricing.PricingCalculator;',
    'import com.example.shop.pricing.NoDiscount;\nimport com.example.shop.pricing.PricingCalculator;')
V2[BASE + 'web/OrderController.java'] = '''package com.example.shop.web;

import com.example.shop.domain.Order;
import com.example.shop.service.OrderService;
import com.example.shop.shipping.ShippingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {
    private final OrderService orders;
    private final ShippingService shipping = new ShippingService();

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

    public boolean shippable(long id) {
        Order order = orders.find(id);
        return shipping.canShip(order);
    }
}
'''


def write(version, files):
    root = os.path.join('shop-' + version)
    for rel, content in {**COMMON, **files}.items():
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w', encoding='utf-8', newline='\n') as f:
            f.write(content)
    with zipfile.ZipFile('shop-' + version + '.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for rel in sorted({**COMMON, **files}):
            archive.write(os.path.join(root, rel), 'shop/' + rel)
    print('wrote shop-' + version, len({**COMMON, **files}), 'files')


if __name__ == '__main__':
    write('v1', V1)
    write('v2', V2)
