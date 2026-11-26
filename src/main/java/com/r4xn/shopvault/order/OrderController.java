package com.r4xn.shopvault.order;

import com.r4xn.shopvault.common.PageResponse;
import com.r4xn.shopvault.order.OrderDtos.CheckoutRequest;
import com.r4xn.shopvault.order.OrderDtos.CheckoutResult;
import com.r4xn.shopvault.order.OrderDtos.OrderResponse;
import com.r4xn.shopvault.order.OrderDtos.OrderSummary;
import com.r4xn.shopvault.order.OrderDtos.PaymentSessionResponse;
import com.r4xn.shopvault.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    /**
     * Checkout. Clients send a fresh Idempotency-Key per purchase attempt and reuse it on retry;
     * a replay returns the original order with {@code Idempotent-Replayed: true}.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> checkout(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "^[A-Za-z0-9_-]{16,64}$",
                    message = "must be 16-64 characters of letters, digits, '-' or '_'") String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request) {
        UUID userId = CurrentUser.id(jwt);
        CheckoutResult result;
        try {
            result = orders.checkout(userId, idempotencyKey, request);
        } catch (DataIntegrityViolationException race) {
            // A concurrent request with the same key won the insert; return its order.
            result = orders.findByIdempotencyKey(userId, idempotencyKey)
                    .map(o -> new CheckoutResult(o, false))
                    .orElseThrow(() -> race);
        }
        if (result.created()) {
            return ResponseEntity.created(URI.create("/api/v1/orders/" + result.order().id())).body(result.order());
        }
        return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.order());
    }

    @GetMapping
    public PageResponse<OrderSummary> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orders.listMine(CurrentUser.id(jwt), page, size);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return orders.getMine(CurrentUser.id(jwt), id);
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return orders.cancelMine(CurrentUser.id(jwt), id);
    }

    @PostMapping("/{id}/payment")
    public PaymentSessionResponse pay(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return orders.startPayment(CurrentUser.id(jwt), id);
    }
}
