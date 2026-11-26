package com.r4xn.shopvault.cart;

import com.r4xn.shopvault.catalog.Product;
import com.r4xn.shopvault.catalog.ProductRepository;
import com.r4xn.shopvault.common.ApiException;
import com.r4xn.shopvault.config.AppProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartService {

    private final CartItemRepository items;
    private final ProductRepository products;
    private final AppProperties.Shop shop;

    public CartService(CartItemRepository items, ProductRepository products, AppProperties props) {
        this.items = items;
        this.products = products;
        this.shop = props.shop();
    }

    @Transactional(readOnly = true)
    public CartResponse view(UUID userId) {
        List<CartLineResponse> lines = items.findWithProducts(userId).stream()
                .map(item -> {
                    Product p = item.getProduct();
                    boolean available = p.isActive() && p.getStock() >= item.getQuantity();
                    return new CartLineResponse(p.getId(), p.getSku(), p.getName(), p.getPriceCents(),
                            item.getQuantity(), p.getPriceCents() * item.getQuantity(), available);
                })
                .toList();
        long subtotal = lines.stream().mapToLong(CartLineResponse::lineTotalCents).sum();
        return new CartResponse(lines, subtotal, shop.currency());
    }

    @Transactional
    public CartResponse setQuantity(UUID userId, UUID productId, int quantity) {
        if (quantity < 1 || quantity > shop.maxQuantityPerLine()) {
            throw ApiException.badRequest("invalid_quantity",
                    "Quantity must be between 1 and " + shop.maxQuantityPerLine());
        }
        Product product = products.findById(productId)
                .filter(Product::isActive)
                .orElseThrow(() -> ApiException.notFound("Product"));
        if (quantity > product.getStock()) {
            throw ApiException.conflict("insufficient_stock", "Only " + product.getStock() + " left in stock");
        }
        items.findByUserIdAndProductId(userId, productId).ifPresentOrElse(
                item -> item.setQuantity(quantity),
                () -> {
                    if (items.countByUserId(userId) >= shop.maxCartLines()) {
                        throw ApiException.conflict("cart_full",
                                "A cart can hold at most " + shop.maxCartLines() + " different products");
                    }
                    items.save(new CartItem(userId, product, quantity));
                });
        items.flush();
        return view(userId);
    }

    @Transactional
    public CartResponse removeLine(UUID userId, UUID productId) {
        items.deleteLine(userId, productId);
        return view(userId);
    }

    @Transactional
    public void clear(UUID userId) {
        items.deleteAllForUser(userId);
    }

    public record CartLineResponse(
            UUID productId, String sku, String name, long unitPriceCents, int quantity, long lineTotalCents,
            boolean available) {}

    public record CartResponse(List<CartLineResponse> items, long subtotalCents, String currency) {}
}
