package com.r4xn.shopvault.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/** Snapshot of what was bought at what price, so later catalogue edits never change an order. */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "unit_price_cents", nullable = false)
    private long unitPriceCents;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "line_total_cents", nullable = false)
    private long lineTotalCents;

    protected OrderItem() {}

    OrderItem(CustomerOrder order, int lineNo, UUID productId, String sku, String productName, long unitPriceCents,
            int quantity) {
        this.order = order;
        this.lineNo = lineNo;
        this.productId = productId;
        this.sku = sku;
        this.productName = productName;
        this.unitPriceCents = unitPriceCents;
        this.quantity = quantity;
        this.lineTotalCents = Math.multiplyExact(unitPriceCents, quantity);
    }

    public UUID getProductId() {
        return productId;
    }

    public String getSku() {
        return sku;
    }

    public String getProductName() {
        return productName;
    }

    public long getUnitPriceCents() {
        return unitPriceCents;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getLineTotalCents() {
        return lineTotalCents;
    }
}
