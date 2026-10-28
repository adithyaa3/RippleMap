package com.example.shop.payment;

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
