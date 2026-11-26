package com.r4xn.shopvault.order;

import com.r4xn.shopvault.common.PageResponse;
import com.r4xn.shopvault.order.OrderDtos.AdminOrderSummary;
import com.r4xn.shopvault.order.OrderDtos.OrderResponse;
import com.r4xn.shopvault.order.OrderDtos.StatusUpdateRequest;
import com.r4xn.shopvault.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/orders")
@PreAuthorize("hasRole('ADMIN')")
public class AdminOrderController {

    private final OrderService orders;

    public AdminOrderController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    public PageResponse<AdminOrderSummary> list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return orders.adminList(status, page, size);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable UUID id) {
        return orders.adminGet(id);
    }

    @PatchMapping("/{id}/status")
    public OrderResponse updateStatus(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody StatusUpdateRequest request) {
        return orders.adminUpdateStatus(CurrentUser.id(jwt), id, request.status());
    }
}
