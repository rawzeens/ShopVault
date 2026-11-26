package com.r4xn.shopvault.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Every webhook event id is stored once, so provider retries are processed exactly once. */
@Entity
@Table(name = "payment_events")
public class PaymentEvent {

    @Id
    @Column(name = "event_id", length = 100)
    private String eventId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false, length = 40)
    private String outcome;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected PaymentEvent() {}

    PaymentEvent(String eventId, UUID orderId, String eventType, String outcome, Instant receivedAt) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.eventType = eventType;
        this.outcome = outcome;
        this.receivedAt = receivedAt;
    }

    public String getOutcome() {
        return outcome;
    }
}
