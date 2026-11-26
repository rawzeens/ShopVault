package com.r4xn.shopvault.payment;

import com.r4xn.shopvault.audit.AuditService;
import com.r4xn.shopvault.order.CustomerOrder;
import com.r4xn.shopvault.order.OrderRepository;
import com.r4xn.shopvault.order.OrderStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentWebhookService {

    public static final String PAYMENT_SUCCEEDED = "payment.succeeded";
    public static final String PAYMENT_FAILED = "payment.failed";

    private final PaymentEventRepository events;
    private final OrderRepository orders;
    private final AuditService audit;
    private final Clock clock;

    public PaymentWebhookService(
            PaymentEventRepository events, OrderRepository orders, AuditService audit, Clock clock) {
        this.events = events;
        this.orders = orders;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Applies a verified event. An order is only marked paid when the amount and currency match
     * the stored total exactly; anything odd is recorded and audited instead of applied.
     */
    @Transactional
    public String handle(WebhookEvent event) {
        if (events.existsById(event.eventId())) {
            return "DUPLICATE";
        }
        Optional<CustomerOrder> found = orders.findByPaymentReferenceForUpdate(event.paymentReference());
        UUID orderId = found.map(CustomerOrder::getId).orElse(null);
        String target = orderId == null ? "payment:" + event.paymentReference() : "order:" + orderId;
        String outcome;
        if (found.isEmpty()) {
            outcome = "UNKNOWN_REFERENCE";
            audit.record(null, "PAYMENT_UNKNOWN_REFERENCE", target, event.eventId());
        } else if (PAYMENT_SUCCEEDED.equals(event.type())) {
            outcome = applySuccess(found.get(), event, target);
        } else if (PAYMENT_FAILED.equals(event.type())) {
            outcome = "RECORDED_FAILURE";
            audit.record(null, "PAYMENT_FAILED", target, event.eventId());
        } else {
            outcome = "IGNORED_TYPE";
        }
        events.save(new PaymentEvent(event.eventId(), orderId, event.type(), outcome, clock.instant()));
        return outcome;
    }

    private String applySuccess(CustomerOrder order, WebhookEvent event, String target) {
        if (event.amountCents() != order.getTotalCents() || !order.getCurrency().equals(event.currency())) {
            audit.record(null, "PAYMENT_AMOUNT_MISMATCH", target, "expected " + order.getTotalCents() + " "
                    + order.getCurrency() + ", got " + event.amountCents() + " " + event.currency());
            return "AMOUNT_MISMATCH";
        }
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            order.markPaid(clock.instant());
            audit.record(null, "PAYMENT_CONFIRMED", target, event.eventId());
            return "APPLIED";
        }
        if (order.getStatus() == OrderStatus.PAID) {
            return "ALREADY_PAID";
        }
        // Paid after the order expired or was cancelled: money must go back to the customer.
        audit.record(null, "PAYMENT_FOR_CLOSED_ORDER", target,
                "order is " + order.getStatus() + "; refund required (" + event.eventId() + ")");
        return "REFUND_REQUIRED";
    }

    public record WebhookEvent(
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9_.:-]+$") String eventId,
            @NotBlank @Size(max = 50) @Pattern(regexp = "^[a-z_.]+$") String type,
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9_.:-]+$") String paymentReference,
            @NotNull @PositiveOrZero Long amountCents,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {}
}
