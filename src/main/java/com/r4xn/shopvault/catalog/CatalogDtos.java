package com.r4xn.shopvault.catalog;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class CatalogDtos {

    private CatalogDtos() {}

    public enum ProductSort {
        NEWEST, PRICE_ASC, PRICE_DESC, NAME
    }

    public record CategoryRequest(
            @NotBlank @Size(max = 80) @Pattern(regexp = "^[^\\p{Cntrl}]+$") String name,
            @NotBlank @Size(max = 80) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                    message = "must be lowercase letters, digits and single dashes") String slug) {}

    public record CategoryResponse(UUID id, String name, String slug) {
        static CategoryResponse from(Category c) {
            return new CategoryResponse(c.getId(), c.getName(), c.getSlug());
        }
    }

    public record ProductRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String sku,
            @NotBlank @Size(max = 200) @Pattern(regexp = "^[^\\p{Cntrl}]+$") String name,
            @Size(max = 4000) String description,
            @NotNull @PositiveOrZero @Max(100_000_000) Long priceCents,
            @NotNull @PositiveOrZero @Max(1_000_000) Integer stock,
            UUID categoryId,
            Boolean active) {}

    /** Public view: shows availability, not exact stock levels. */
    public record ProductResponse(
            UUID id, String sku, String name, String description, long priceCents, String currency,
            boolean inStock, CategoryResponse category) {}

    /** Admin view: includes stock, status and timestamps. */
    public record AdminProductResponse(
            UUID id, String sku, String name, String description, long priceCents, String currency,
            int stock, boolean active, CategoryResponse category, Instant createdAt) {}
}
