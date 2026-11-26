package com.r4xn.shopvault.cart;

import com.r4xn.shopvault.cart.CartService.CartResponse;
import com.r4xn.shopvault.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Every operation is scoped to the caller's own cart; there is no cart id to tamper with. */
@RestController
@RequestMapping("/api/v1/cart")
public class CartController {

    private final CartService cart;

    public CartController(CartService cart) {
        this.cart = cart;
    }

    @GetMapping
    public CartResponse view(@AuthenticationPrincipal Jwt jwt) {
        return cart.view(CurrentUser.id(jwt));
    }

    @PutMapping("/items/{productId}")
    public CartResponse setQuantity(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId, @Valid @RequestBody QuantityRequest request) {
        return cart.setQuantity(CurrentUser.id(jwt), productId, request.quantity());
    }

    @DeleteMapping("/items/{productId}")
    public CartResponse remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId) {
        return cart.removeLine(CurrentUser.id(jwt), productId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@AuthenticationPrincipal Jwt jwt) {
        cart.clear(CurrentUser.id(jwt));
    }

    public record QuantityRequest(@NotNull @Min(1) @Max(999) Integer quantity) {}
}
