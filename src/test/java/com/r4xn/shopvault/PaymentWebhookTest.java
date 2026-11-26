package com.r4xn.shopvault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.r4xn.shopvault.audit.AuditEvent;
import com.r4xn.shopvault.audit.AuditRepository;
import com.r4xn.shopvault.payment.WebhookSignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

class PaymentWebhookTest extends ApiTestSupport {

    @Autowired
    WebhookSignatureVerifier verifier;

    @Autowired
    AuditRepository auditRepository;

    private record PendingOrder(String customer, String orderId, String reference, long total) {}

    private PendingOrder pendingOrder() throws Exception {
        String customer = newCustomer().access();
        String orderId = placeOrder(customer, createProduct(adminToken(), 4_200, 10), 1);
        ResultActions payment = mvc.perform(post("/api/v1/orders/" + orderId + "/payment")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountCents").value(5_200));
        return new PendingOrder(customer, orderId, json(payment, "$.paymentReference"), 5_200);
    }

    private static String event(String eventId, String type, String reference, long amount, String currency) {
        return """
                {"eventId":"%s","type":"%s","paymentReference":"%s","amountCents":%d,"currency":"%s"}"""
                .formatted(eventId, type, reference, amount, currency);
    }

    private ResultActions send(String body, String signatureHeader) throws Exception {
        var request = post("/api/v1/payments/webhook").contentType(JSON).content(body);
        if (signatureHeader != null) {
            request.header(WebhookSignatureVerifier.HEADER, signatureHeader);
        }
        return mvc.perform(request);
    }

    private String sign(String body, long timestamp) {
        String t = Long.toString(timestamp);
        return "t=" + t + ",v1=" + verifier.sign(t, body.getBytes(StandardCharsets.UTF_8));
    }

    private void expectStatus(PendingOrder order, String status) throws Exception {
        mvc.perform(get("/api/v1/orders/" + order.orderId()).header(HttpHeaders.AUTHORIZATION, bearer(order.customer())))
                .andExpect(jsonPath("$.status").value(status));
    }

    @Test
    void signedSuccessEventMarksTheOrderPaid() throws Exception {
        PendingOrder order = pendingOrder();
        String body = event("evt_" + UUID.randomUUID(), "payment.succeeded", order.reference(), order.total(), "MYR");

        send(body, sign(body, Instant.now().getEpochSecond())).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/orders/" + order.orderId()).header(HttpHeaders.AUTHORIZATION, bearer(order.customer())))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidAt").isNotEmpty());
    }

    @Test
    void unsignedOrBadlySignedEventsAreRejected() throws Exception {
        PendingOrder order = pendingOrder();
        String body = event("evt_" + UUID.randomUUID(), "payment.succeeded", order.reference(), order.total(), "MYR");
        long now = Instant.now().getEpochSecond();

        send(body, null).andExpect(status().isUnauthorized());
        send(body, "t=" + now + ",v1=" + "0".repeat(64)).andExpect(status().isUnauthorized());
        // Signature computed for a different body (e.g. attacker changed the amount).
        String other = event("evt_x", "payment.succeeded", order.reference(), 1, "MYR");
        send(body, sign(other, now)).andExpect(status().isUnauthorized());
        // A correctly signed but old message cannot be replayed.
        send(body, sign(body, now - 3600)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_signature"));

        expectStatus(order, "PENDING_PAYMENT");
    }

    @Test
    void amountMismatchIsNotAppliedAndIsAudited() throws Exception {
        PendingOrder order = pendingOrder();
        String body = event("evt_" + UUID.randomUUID(), "payment.succeeded", order.reference(), 100, "MYR");
        send(body, sign(body, Instant.now().getEpochSecond())).andExpect(status().isNoContent());

        expectStatus(order, "PENDING_PAYMENT");
        assertThat(auditRepository.findByTargetOrderByOccurredAtAsc("order:" + order.orderId()))
                .extracting(AuditEvent::getAction).contains("PAYMENT_AMOUNT_MISMATCH");
    }

    @Test
    void duplicateDeliveriesAreProcessedOnce() throws Exception {
        PendingOrder order = pendingOrder();
        String body = event("evt_" + UUID.randomUUID(), "payment.succeeded", order.reference(), order.total(), "MYR");
        long now = Instant.now().getEpochSecond();

        send(body, sign(body, now)).andExpect(status().isNoContent());
        send(body, sign(body, now)).andExpect(status().isNoContent());

        expectStatus(order, "PAID");
        assertThat(auditRepository.findByTargetOrderByOccurredAtAsc("order:" + order.orderId()))
                .extracting(AuditEvent::getAction).containsOnlyOnce("PAYMENT_CONFIRMED");
    }

    @Test
    void paymentForACancelledOrderIsFlaggedForRefund() throws Exception {
        PendingOrder order = pendingOrder();
        mvc.perform(post("/api/v1/orders/" + order.orderId() + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, bearer(order.customer()))).andExpect(status().isOk());

        String body = event("evt_" + UUID.randomUUID(), "payment.succeeded", order.reference(), order.total(), "MYR");
        send(body, sign(body, Instant.now().getEpochSecond())).andExpect(status().isNoContent());

        expectStatus(order, "CANCELLED");
        assertThat(auditRepository.findByTargetOrderByOccurredAtAsc("order:" + order.orderId()))
                .extracting(AuditEvent::getAction).contains("PAYMENT_FOR_CLOSED_ORDER");
    }

    @Test
    void malformedButSignedBodiesAreRejected() throws Exception {
        String body = "{\"eventId\":\"<script>\",\"type\":\"payment.succeeded\"}";
        send(body, sign(body, Instant.now().getEpochSecond()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_event"));
    }
}
