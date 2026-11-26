package com.r4xn.shopvault.payment;

import com.r4xn.shopvault.common.ApiException;
import com.r4xn.shopvault.payment.PaymentWebhookService.WebhookEvent;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Payment provider callbacks. Not protected by JWT: the body is accepted only if its HMAC
 * signature verifies, and the raw bytes are verified before any JSON parsing.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final WebhookSignatureVerifier verifier;
    private final PaymentWebhookService service;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public PaymentWebhookController(
            WebhookSignatureVerifier verifier,
            PaymentWebhookService service,
            ObjectMapper objectMapper,
            Validator validator) {
        this.verifier = verifier;
        this.service = service;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @PostMapping(path = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> webhook(
            @RequestHeader(name = WebhookSignatureVerifier.HEADER, required = false) String signature,
            @RequestBody byte[] body) {
        verifier.verify(signature, body);
        WebhookEvent event;
        try {
            event = objectMapper.readValue(body, WebhookEvent.class);
        } catch (JacksonException e) {
            throw ApiException.badRequest("invalid_event", "The webhook body is not a valid event");
        }
        Set<ConstraintViolation<WebhookEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            throw ApiException.badRequest("invalid_event", "The webhook body is not a valid event");
        }
        String outcome = service.handle(event);
        log.info("Payment webhook {} ({}) -> {}", event.eventId(), event.type(), outcome);
        return ResponseEntity.noContent().build();
    }
}
