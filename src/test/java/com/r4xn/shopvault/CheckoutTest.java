package com.r4xn.shopvault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.r4xn.shopvault.order.OrderService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

class CheckoutTest extends ApiTestSupport {

    @Autowired
    OrderService orderService;

    @Test
    void totalsAreComputedFromServerPricesNotFromTheClient() throws Exception {
        String admin = adminToken();
        String productId = createProduct(admin, 2_500, 10);
        String customer = newCustomer().access();
        addToCart(customer, productId, 2);

        String tampered = checkoutBody().replaceFirst("\\{", "{\"totalCents\":1,\"subtotalCents\":1,\"status\":\"PAID\",");
        mvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(customer))
                        .header("Idempotency-Key", newIdempotencyKey()).contentType(JSON).content(tampered))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.subtotalCents").value(5_000))
                .andExpect(jsonPath("$.shippingCents").value(1_000))
                .andExpect(jsonPath("$.totalCents").value(6_000))
                .andExpect(jsonPath("$.items[0].unitPriceCents").value(2_500))
                .andExpect(jsonPath("$.currency").value("MYR"));

        assertThat(stockOf(admin, productId)).isEqualTo(8);
        mvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void shippingIsFreeAboveTheThreshold() throws Exception {
        String productId = createProduct(adminToken(), 8_000, 10);
        String customer = newCustomer().access();
        addToCart(customer, productId, 2);
        checkout(customer, newIdempotencyKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shippingCents").value(0))
                .andExpect(jsonPath("$.totalCents").value(16_000));
    }

    @Test
    void retryWithTheSameIdempotencyKeyReturnsTheSameOrder() throws Exception {
        String admin = adminToken();
        String productId = createProduct(admin, 1_000, 10);
        String customer = newCustomer().access();
        addToCart(customer, productId, 3);
        String key = newIdempotencyKey();

        String firstId = json(checkout(customer, key).andExpect(status().isCreated()), "$.id");
        ResultActions replay = checkout(customer, key)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        assertThat((String) json(replay, "$.id")).isEqualTo(firstId);
        assertThat(stockOf(admin, productId)).isEqualTo(7);
    }

    @Test
    void checkoutFailsWithoutTakingStockWhenStockRanOut() throws Exception {
        String admin = adminToken();
        String productId = createProduct(admin, 1_000, 2);
        String customer = newCustomer().access();
        addToCart(customer, productId, 2);

        // Stock drops after the item went into the cart.
        mvc.perform(put("/api/v1/admin/products/" + productId).header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(JSON)
                        .content("{\"sku\":\"LOW-%s\",\"name\":\"Low stock\",\"priceCents\":1000,\"stock\":1}"
                                .formatted(productId.substring(0, 8))))
                .andExpect(status().isOk());

        checkout(customer, newIdempotencyKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("insufficient_stock"));
        assertThat(stockOf(admin, productId)).isEqualTo(1);
    }

    @Test
    void concurrentCheckoutsNeverOversell() throws Exception {
        String admin = adminToken();
        String productId = createProduct(admin, 1_000, 5);
        int buyers = 8;
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            String token = newCustomer().access();
            addToCart(token, productId, 1);
            tokens.add(token);
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String token : tokens) {
                results.add(pool.submit(() -> {
                    start.await();
                    return checkout(token, newIdempotencyKey()).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(5);
            assertThat(statuses).filteredOn(s -> s != 201).allMatch(s -> s == 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stockOf(admin, productId)).isZero();
    }

    @Test
    void cartRejectsMoreThanIsInStock() throws Exception {
        String productId = createProduct(adminToken(), 1_000, 1);
        String customer = newCustomer().access();
        mvc.perform(put("/api/v1/cart/items/" + productId).header(HttpHeaders.AUTHORIZATION, bearer(customer))
                        .contentType(JSON).content("{\"quantity\":5}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/cart/items/" + productId).header(HttpHeaders.AUTHORIZATION, bearer(customer))
                        .contentType(JSON).content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checkoutRequiresAValidIdempotencyKeyAndANonEmptyCart() throws Exception {
        String customer = newCustomer().access();
        mvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(customer))
                        .contentType(JSON).content(checkoutBody()))
                .andExpect(status().isBadRequest());
        checkout(customer, "short").andExpect(status().isBadRequest());
        checkout(customer, newIdempotencyKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("cart_empty"));
    }

    @Test
    void invalidAddressIsRejected() throws Exception {
        String productId = createProduct(adminToken(), 1_000, 5);
        String customer = newCustomer().access();
        addToCart(customer, productId, 1);
        mvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(customer))
                        .header("Idempotency-Key", newIdempotencyKey()).contentType(JSON)
                        .content(checkoutBody().replace("\"MY\"", "\"Malaysia\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[0].field").value("shippingAddress.country"));
    }

    @Test
    void cancellingAnUnpaidOrderReturnsTheStock() throws Exception {
        String admin = adminToken();
        String productId = createProduct(admin, 1_000, 5);
        String customer = newCustomer().access();
        String orderId = placeOrder(customer, productId, 4);
        assertThat(stockOf(admin, productId)).isEqualTo(1);

        mvc.perform(post("/api/v1/orders/" + orderId + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(stockOf(admin, productId)).isEqualTo(5);

        mvc.perform(post("/api/v1/orders/" + orderId + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isConflict());
    }

    @Test
    void unpaidOrdersExpireAndReleaseStock() throws Exception {
        String admin = adminToken();
        String productId = createProduct(admin, 1_000, 3);
        String customer = newCustomer().access();
        String orderId = placeOrder(customer, productId, 3);
        assertThat(stockOf(admin, productId)).isZero();

        assertThat(orderService.expireUnpaidOrders(Instant.now().plusSeconds(60))).isGreaterThanOrEqualTo(1);

        mvc.perform(get("/api/v1/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(stockOf(admin, productId)).isEqualTo(3);
    }

    @Test
    void adminCannotMarkOrdersPaidByHand() throws Exception {
        String admin = adminToken();
        String orderId = placeOrder(newCustomer().access(), createProduct(admin, 1_000, 5), 1);
        mvc.perform(patch(
                                "/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(JSON).content("{\"status\":\"PAID\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(
                                "/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(JSON).content("{\"status\":\"SHIPPED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invalid_transition"));
    }
}
