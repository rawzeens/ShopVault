package com.r4xn.shopvault.catalog;

import com.r4xn.shopvault.common.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Money is stored as integer minor units (sen/cents) to avoid floating-point rounding. */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 4000)
    private String description;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Column(nullable = false)
    private int stock;

    @Column(nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Product() {}

    public Product(String sku, String name, String description, long priceCents, int stock, Category category) {
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.priceCents = priceCents;
        this.stock = stock;
        this.category = category;
    }

    public void update(String sku, String name, String description, long priceCents, int stock, Category category,
            boolean active) {
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.priceCents = priceCents;
        this.stock = stock;
        this.category = category;
        this.active = active;
    }

    /** Only called while holding a row lock (see ProductRepository#findAllByIdForUpdate). */
    public void takeStock(int quantity) {
        if (quantity <= 0 || quantity > stock) {
            throw ApiException.conflict("insufficient_stock", "Not enough stock for '" + name + "'");
        }
        stock -= quantity;
    }

    public void returnStock(int quantity) {
        stock += quantity;
    }

    public void deactivate() {
        active = false;
    }

    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public long getPriceCents() {
        return priceCents;
    }

    public int getStock() {
        return stock;
    }

    public boolean isActive() {
        return active;
    }

    public Category getCategory() {
        return category;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
