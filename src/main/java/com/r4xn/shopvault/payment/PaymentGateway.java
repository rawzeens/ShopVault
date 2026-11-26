package com.r4xn.shopvault.payment;

import java.util.UUID;

/**
 * Port to a payment provider (Stripe, Billplz, ToyyibPay, iPay88, ...). Card data never touches
 * this server: the customer pays on the provider's hosted page and the provider confirms via
 * the signed webhook handled by {@link PaymentWebhookController}.
 */
public interface PaymentGateway {

    /** Creates a payment for the given amount and returns the provider's reference for it. */
    String createPayment(UUID orderId, long amountCents, String currency);

    /** Where the customer is sent to pay. */
    String checkoutUrl(String paymentReference);
}
