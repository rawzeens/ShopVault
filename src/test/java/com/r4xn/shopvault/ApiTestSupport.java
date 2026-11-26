package com.r4xn.shopvault;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Full application with an in-memory database, driven through the real HTTP + security stack. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ApiTestSupport {

    protected static final String PASSWORD = "correct-Horse-battery-42";
    protected static final String ADMIN_EMAIL = "admin@test.shopvault";
    protected static final String ADMIN_PASSWORD = "test-only-Shop-Owner-2026";
    protected static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    protected MockMvc mvc;

    protected record Tokens(String access, String refresh) {}

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected static String newIdempotencyKey() {
        return "checkout-" + UUID.randomUUID();
    }

    protected static <T> T json(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
    }

    protected Tokens register(String email) throws Exception {
        ResultActions result = mvc.perform(post("/api/v1/auth/register").contentType(JSON).content("""
                {"email":"%s","password":"%s","fullName":"Test Customer"}""".formatted(email, PASSWORD)))
                .andExpect(status().isCreated());
        return new Tokens(json(result, "$.accessToken"), json(result, "$.refreshToken"));
    }

    protected Tokens newCustomer() throws Exception {
        return register(uniqueEmail());
    }

    protected ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(JSON).content("""
                {"email":"%s","password":"%s"}""".formatted(email, password)));
    }

    protected Tokens loginOk(String email, String password) throws Exception {
        ResultActions result = login(email, password).andExpect(status().isOk());
        return new Tokens(json(result, "$.accessToken"), json(result, "$.refreshToken"));
    }

    protected String adminToken() throws Exception {
        return loginOk(ADMIN_EMAIL, ADMIN_PASSWORD).access();
    }

    protected String userId(String accessToken) throws Exception {
        return json(mvc.perform(get("/api/v1/account/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk()), "$.id");
    }

    protected String createProduct(String adminToken, long priceCents, int stock) throws Exception {
        String sku = "SKU-" + UUID.randomUUID().toString().substring(0, 8);
        ResultActions result = mvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(JSON)
                        .content("""
                                {"sku":"%s","name":"Test Product %s","description":"A test product",
                                 "priceCents":%d,"stock":%d}""".formatted(sku, sku, priceCents, stock)))
                .andExpect(status().isCreated());
        return json(result, "$.id");
    }

    protected int stockOf(String adminToken, String productId) throws Exception {
        return json(mvc.perform(get("/api/v1/admin/products/" + productId)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))).andExpect(status().isOk()), "$.stock");
    }

    protected void addToCart(String token, String productId, int quantity) throws Exception {
        mvc.perform(put("/api/v1/cart/items/" + productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(JSON)
                        .content("{\"quantity\":" + quantity + "}"))
                .andExpect(status().isOk());
    }

    protected static String checkoutBody() {
        return """
                {"shippingAddress":{"recipientName":"Aisyah Rahman","phone":"+60 12-345 6789",
                 "line1":"12 Jalan Bukit Bintang","line2":null,"city":"Kuala Lumpur","state":"WP Kuala Lumpur",
                 "postcode":"55100","country":"MY"}}""";
    }

    protected ResultActions checkout(String token, String idempotencyKey) throws Exception {
        return mvc.perform(post("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(JSON)
                .content(checkoutBody()));
    }

    /** Customer with one product of the given price/quantity checked out; returns the order id. */
    protected String placeOrder(String token, String productId, int quantity) throws Exception {
        addToCart(token, productId, quantity);
        return json(checkout(token, newIdempotencyKey()).andExpect(status().isCreated()), "$.id");
    }
}
