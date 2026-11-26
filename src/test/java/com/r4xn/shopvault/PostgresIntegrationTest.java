package com.r4xn.shopvault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.r4xn.shopvault.payment.WebhookSignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The production database engine: Flyway migrations, Hibernate schema validation, row locks and
 * the full purchase flow on real PostgreSQL. Skipped when Docker is unavailable; always runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresIntegrationTest extends ApiTestSupport {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    WebhookSignatureVerifier verifier;

    @Test
    void fullPurchaseWorksOnPostgres() throws Exception {
        String admin = adminToken();
        String productId = createProduct(admin, 3_500, 10);
        String customer = newCustomer().access();
        String orderId = placeOrder(customer, productId, 2);
        assertThat(stockOf(admin, productId)).isEqualTo(8);

        String reference = json(mvc.perform(post("/api/v1/orders/" + orderId + "/payment")
                .header(HttpHeaders.AUTHORIZATION, bearer(customer))).andExpect(status().isOk()), "$.paymentReference");
        String body = """
                {"eventId":"evt_%s","type":"payment.succeeded","paymentReference":"%s","amountCents":8000,"currency":"MYR"}"""
                .formatted(UUID.randomUUID(), reference);
        String t = Long.toString(Instant.now().getEpochSecond());
        mvc.perform(post("/api/v1/payments/webhook").contentType(JSON).content(body)
                        .header(WebhookSignatureVerifier.HEADER,
                                "t=" + t + ",v1=" + verifier.sign(t, body.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.totalCents").value(8_000));
    }

    @Test
    void searchEscapingAndLockoutWorkOnPostgres() throws Exception {
        mvc.perform(get("/api/v1/products").param("q", "%' OR 1=1 --"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        String email = uniqueEmail();
        register(email);
        for (int i = 0; i < 5; i++) {
            login(email, "wrong-password-" + i).andExpect(status().isUnauthorized());
        }
        login(email, PASSWORD).andExpect(status().isUnauthorized());
    }
}
