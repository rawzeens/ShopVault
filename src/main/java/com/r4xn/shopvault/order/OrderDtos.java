package com.r4xn.shopvault.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {

    private OrderDtos() {}

    private static final String NO_CONTROL = "^[^\\p{Cntrl}]*$";

    public record ShippingAddressDto(
            @NotBlank @Size(max = 100) @Pattern(regexp = NO_CONTROL) String recipientName,
            @NotBlank @Pattern(regexp = "^\\+?[0-9][0-9 -]{6,18}$", message = "must be a valid phone number") String phone,
            @NotBlank @Size(max = 200) @Pattern(regexp = NO_CONTROL) String line1,
            @Size(max = 200) @Pattern(regexp = NO_CONTROL) String line2,
            @NotBlank @Size(max = 100) @Pattern(regexp = NO_CONTROL) String city,
            @NotBlank @Size(max = 100) @Pattern(regexp = NO_CONTROL) String state,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9 -]{3,10}$", message = "must be a valid postcode") String postcode,
            @NotBlank @Pattern(regexp = "^[A-Z]{2}$", message = "must be an ISO 3166-1 alpha-2 code") String country) {

        ShippingAddress toEmbeddable() {
            return new ShippingAddress(recipientName.strip(), phone.strip(), line1.strip(),
                    line2 == null || line2.isBlank() ? null : line2.strip(), city.strip(), state.strip(),
                    postcode.strip(), country);
        }

        static ShippingAddressDto from(ShippingAddress a) {
            return new ShippingAddressDto(a.recipientName(), a.phone(), a.line1(), a.line2(), a.city(), a.state(),
                    a.postcode(), a.country());
        }
    }

    /** No prices, totals or product details are accepted from the client: the server computes them. */
    public record CheckoutRequest(@NotNull @Valid ShippingAddressDto shippingAddress) {}

    public record StatusUpdateRequest(@NotNull OrderStatus status) {}

    public record OrderItemResponse(
            UUID productId, String sku, String name, long unitPriceCents, int quantity, long lineTotalCents) {
        static OrderItemResponse from(OrderItem i) {
            return new OrderItemResponse(i.getProductId(), i.getSku(), i.getProductName(), i.getUnitPriceCents(),
                    i.getQuantity(), i.getLineTotalCents());
        }
    }

    public record OrderResponse(
            UUID id, OrderStatus status, String currency, long subtotalCents, long shippingCents, long totalCents,
            ShippingAddressDto shippingAddress, List<OrderItemResponse> items, Instant createdAt, Instant paidAt) {
        static OrderResponse from(CustomerOrder o) {
            return new OrderResponse(o.getId(), o.getStatus(), o.getCurrency(), o.getSubtotalCents(),
                    o.getShippingCents(), o.getTotalCents(), ShippingAddressDto.from(o.getShippingAddress()),
                    o.getItems().stream().map(OrderItemResponse::from).toList(), o.getCreatedAt(), o.getPaidAt());
        }
    }

    public record OrderSummary(UUID id, OrderStatus status, long totalCents, String currency, Instant createdAt) {
        static OrderSummary from(CustomerOrder o) {
            return new OrderSummary(o.getId(), o.getStatus(), o.getTotalCents(), o.getCurrency(), o.getCreatedAt());
        }
    }

    public record AdminOrderSummary(
            UUID id, UUID userId, OrderStatus status, long totalCents, String currency, Instant createdAt,
            Instant paidAt) {
        static AdminOrderSummary from(CustomerOrder o) {
            return new AdminOrderSummary(o.getId(), o.getUserId(), o.getStatus(), o.getTotalCents(), o.getCurrency(),
                    o.getCreatedAt(), o.getPaidAt());
        }
    }

    public record PaymentSessionResponse(
            UUID orderId, String paymentReference, String checkoutUrl, long amountCents, String currency) {}

    public record CheckoutResult(OrderResponse order, boolean created) {}
}
