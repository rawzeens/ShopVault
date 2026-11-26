package com.r4xn.shopvault.order;

import com.r4xn.shopvault.audit.AuditService;
import com.r4xn.shopvault.cart.CartItemRepository;
import com.r4xn.shopvault.cart.CartLine;
import com.r4xn.shopvault.catalog.Product;
import com.r4xn.shopvault.catalog.ProductRepository;
import com.r4xn.shopvault.common.ApiException;
import com.r4xn.shopvault.common.PageResponse;
import com.r4xn.shopvault.config.AppProperties;
import com.r4xn.shopvault.order.OrderDtos.AdminOrderSummary;
import com.r4xn.shopvault.order.OrderDtos.CheckoutRequest;
import com.r4xn.shopvault.order.OrderDtos.CheckoutResult;
import com.r4xn.shopvault.order.OrderDtos.OrderResponse;
import com.r4xn.shopvault.order.OrderDtos.OrderSummary;
import com.r4xn.shopvault.order.OrderDtos.PaymentSessionResponse;
import com.r4xn.shopvault.payment.PaymentGateway;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final CartItemRepository cartItems;
    private final ProductRepository products;
    private final PaymentGateway paymentGateway;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final AppProperties.Shop shop;

    public OrderService(
            OrderRepository orders,
            CartItemRepository cartItems,
            ProductRepository products,
            PaymentGateway paymentGateway,
            AuditService audit,
            TransactionTemplate transactions,
            AppProperties props) {
        this.orders = orders;
        this.cartItems = cartItems;
        this.products = products;
        this.paymentGateway = paymentGateway;
        this.audit = audit;
        this.transactions = transactions;
        this.shop = props.shop();
    }

    /**
     * Turns the caller's cart into an order. Prices come from the database, product rows are
     * locked while stock is taken, and the Idempotency-Key makes retries return the same order
     * instead of charging twice.
     */
    @Transactional
    public CheckoutResult checkout(UUID userId, String idempotencyKey, CheckoutRequest request) {
        Optional<CustomerOrder> existing = orders.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            return new CheckoutResult(OrderResponse.from(existing.get()), false);
        }
        List<CartLine> lines = cartItems.findLines(userId).stream()
                .sorted(Comparator.comparing(CartLine::productId))
                .toList();
        if (lines.isEmpty()) {
            throw ApiException.conflict("cart_empty", "Your cart is empty");
        }
        Map<UUID, Product> locked = products.findAllByIdForUpdate(lines.stream().map(CartLine::productId).toList())
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        CustomerOrder order = new CustomerOrder(
                userId, idempotencyKey, shop.currency(), request.shippingAddress().toEmbeddable());
        for (CartLine line : lines) {
            Product product = locked.get(line.productId());
            if (product == null || !product.isActive()) {
                throw ApiException.conflict("product_unavailable", "A product in your cart is no longer available");
            }
            if (line.quantity() > shop.maxQuantityPerLine()) {
                throw ApiException.conflict("invalid_quantity", "Quantity too large for '" + product.getName() + "'");
            }
            product.takeStock(line.quantity());
            order.addItem(product.getId(), product.getSku(), product.getName(), product.getPriceCents(),
                    line.quantity());
        }
        boolean freeShipping = order.getSubtotalCents() >= shop.freeShippingThresholdCents();
        order.setShippingCents(freeShipping ? 0 : shop.shippingFlatCents());
        orders.saveAndFlush(order);
        cartItems.deleteAllForUser(userId);
        audit.record(userId, "ORDER_PLACED", "order:" + order.getId(),
                "total=" + order.getTotalCents() + " " + order.getCurrency());
        return new CheckoutResult(OrderResponse.from(order), true);
    }

    @Transactional(readOnly = true)
    public Optional<OrderResponse> findByIdempotencyKey(UUID userId, String idempotencyKey) {
        return orders.findByUserIdAndIdempotencyKey(userId, idempotencyKey).map(OrderResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> listMine(UUID userId, int page, int size) {
        return PageResponse.of(orders.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size)),
                OrderSummary::from);
    }

    @Transactional(readOnly = true)
    public OrderResponse getMine(UUID userId, UUID orderId) {
        return orders.findByIdAndUserId(orderId, userId)
                .map(OrderResponse::from)
                .orElseThrow(() -> ApiException.notFound("Order"));
    }

    @Transactional
    public OrderResponse cancelMine(UUID userId, UUID orderId) {
        CustomerOrder order = orders.findByIdAndUserIdForUpdate(orderId, userId)
                .orElseThrow(() -> ApiException.notFound("Order"));
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw ApiException.conflict("not_cancellable",
                    "Only unpaid orders can be cancelled online; please contact support");
        }
        cancelAndRestock(order);
        audit.record(userId, "ORDER_CANCELLED", "order:" + orderId, "by customer");
        return OrderResponse.from(order);
    }

    /**
     * Opens (or re-opens) a payment session for an unpaid order. The amount sent to the provider
     * is the stored order total, never a client value.
     */
    @Transactional
    public PaymentSessionResponse startPayment(UUID userId, UUID orderId) {
        CustomerOrder order = orders.findByIdAndUserIdForUpdate(orderId, userId)
                .orElseThrow(() -> ApiException.notFound("Order"));
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw ApiException.conflict("not_payable", "This order can no longer be paid");
        }
        if (order.getPaymentReference() == null) {
            order.attachPaymentReference(paymentGateway.createPayment(
                    order.getId(), order.getTotalCents(), order.getCurrency()));
            audit.record(userId, "PAYMENT_STARTED", "order:" + orderId, "reference=" + order.getPaymentReference());
        }
        return new PaymentSessionResponse(order.getId(), order.getPaymentReference(),
                paymentGateway.checkoutUrl(order.getPaymentReference()), order.getTotalCents(), order.getCurrency());
    }

    // ---- admin ----

    @Transactional(readOnly = true)
    public PageResponse<AdminOrderSummary> adminList(OrderStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        var result = status == null
                ? orders.findAllByOrderByCreatedAtDesc(pageable)
                : orders.findByStatusOrderByCreatedAtDesc(status, pageable);
        return PageResponse.of(result, AdminOrderSummary::from);
    }

    @Transactional(readOnly = true)
    public OrderResponse adminGet(UUID orderId) {
        return orders.findById(orderId).map(OrderResponse::from).orElseThrow(() -> ApiException.notFound("Order"));
    }

    /** Fulfilment moves only. PAID is set exclusively by a verified payment webhook. */
    @Transactional
    public OrderResponse adminUpdateStatus(UUID actor, UUID orderId, OrderStatus target) {
        if (target == OrderStatus.PAID || target == OrderStatus.PENDING_PAYMENT) {
            throw ApiException.badRequest("invalid_status", "Payment status is set by the payment provider only");
        }
        CustomerOrder order = orders.findByIdForUpdate(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        OrderStatus from = order.getStatus();
        if (target == OrderStatus.CANCELLED) {
            if (!from.canTransitionTo(OrderStatus.CANCELLED)) {
                throw ApiException.conflict("invalid_transition", "An order cannot move from " + from + " to CANCELLED");
            }
            cancelAndRestock(order);
        } else {
            order.transitionTo(target);
        }
        audit.record(actor, "ORDER_STATUS_CHANGED", "order:" + orderId,
                from + " -> " + target + (from == OrderStatus.PAID && target == OrderStatus.CANCELLED
                        ? " (paid order: refund required)" : ""));
        return OrderResponse.from(order);
    }

    // ---- expiry ----

    /**
     * Cancels unpaid orders created before {@code cutoff} and puts their stock back, each in its
     * own short transaction, re-checking the status under a row lock (a payment may have landed).
     */
    public int expireUnpaidOrders(Instant cutoff) {
        List<UUID> ids = orders.findIdsCreatedBefore(OrderStatus.PENDING_PAYMENT, cutoff, PageRequest.of(0, 500));
        int expired = 0;
        for (UUID id : ids) {
            Boolean done = transactions.execute(tx -> orders.findByIdForUpdate(id)
                    .filter(o -> o.getStatus() == OrderStatus.PENDING_PAYMENT)
                    .map(o -> {
                        cancelAndRestock(o);
                        audit.record(null, "ORDER_EXPIRED", "order:" + id, "unpaid after timeout");
                        return true;
                    })
                    .orElse(false));
            if (Boolean.TRUE.equals(done)) {
                expired++;
            }
        }
        return expired;
    }

    private void cancelAndRestock(CustomerOrder order) {
        order.transitionTo(OrderStatus.CANCELLED);
        Map<UUID, Integer> quantities = new TreeMap<>();
        order.getItems().forEach(i -> quantities.merge(i.getProductId(), i.getQuantity(), Integer::sum));
        products.findAllByIdForUpdate(quantities.keySet())
                .forEach(p -> p.returnStock(quantities.get(p.getId())));
    }
}
