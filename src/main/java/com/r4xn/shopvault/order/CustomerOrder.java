package com.r4xn.shopvault.order;

import com.r4xn.shopvault.common.ApiException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity(name = "CustomerOrder")
@Table(name = "orders")
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status = OrderStatus.PENDING_PAYMENT;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "subtotal_cents", nullable = false)
    private long subtotalCents;

    @Column(name = "shipping_cents", nullable = false)
    private long shippingCents;

    @Column(name = "total_cents", nullable = false)
    private long totalCents;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "payment_reference", unique = true, length = 100)
    private String paymentReference;

    @Embedded
    private ShippingAddress shippingAddress;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<OrderItem> items = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Version
    private long version;

    protected CustomerOrder() {}

    CustomerOrder(UUID userId, String idempotencyKey, String currency, ShippingAddress shippingAddress) {
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.currency = currency;
        this.shippingAddress = shippingAddress;
    }

    void addItem(UUID productId, String sku, String name, long unitPriceCents, int quantity) {
        OrderItem item = new OrderItem(this, items.size() + 1, productId, sku, name, unitPriceCents, quantity);
        items.add(item);
        subtotalCents = Math.addExact(subtotalCents, item.getLineTotalCents());
        totalCents = Math.addExact(subtotalCents, shippingCents);
    }

    void setShippingCents(long shippingCents) {
        this.shippingCents = shippingCents;
        this.totalCents = Math.addExact(subtotalCents, shippingCents);
    }

    void attachPaymentReference(String reference) {
        this.paymentReference = reference;
    }

    public void transitionTo(OrderStatus next) {
        if (!status.canTransitionTo(next)) {
            throw ApiException.conflict("invalid_transition",
                    "An order cannot move from " + status + " to " + next);
        }
        this.status = next;
    }

    public void markPaid(Instant when) {
        transitionTo(OrderStatus.PAID);
        this.paidAt = when;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public long getSubtotalCents() {
        return subtotalCents;
    }

    public long getShippingCents() {
        return shippingCents;
    }

    public long getTotalCents() {
        return totalCents;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public ShippingAddress getShippingAddress() {
        return shippingAddress;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }
}
