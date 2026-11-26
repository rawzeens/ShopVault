package com.r4xn.shopvault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class AccessControlTest extends ApiTestSupport {

    @Test
    void anonymousCallersGetA401ProblemResponse() throws Exception {
        mvc.perform(get("/api/v1/cart"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void customersCannotReachAdminEndpoints() throws Exception {
        String customer = newCustomer().access();
        for (String path : new String[] {"/api/v1/admin/products", "/api/v1/admin/orders", "/api/v1/admin/users",
                "/api/v1/admin/audit"}) {
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("forbidden"));
        }
        mvc.perform(post("/api/v1/admin/products").header(HttpHeaders.AUTHORIZATION, bearer(customer))
                        .contentType(JSON).content("{\"sku\":\"X\",\"name\":\"X\",\"priceCents\":1,\"stock\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void catalogueIsPublicButInactiveProductsAreHidden() throws Exception {
        String admin = adminToken();
        String productId = createProduct(admin, 1_000, 5);

        mvc.perform(get("/api/v1/products/" + productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inStock").value(true))
                .andExpect(jsonPath("$.stock").doesNotExist());

        mvc.perform(delete("/api/v1/admin/products/" + productId).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/products/" + productId)).andExpect(status().isNotFound());
    }

    @Test
    void customersCannotSeeOrTouchOtherCustomersOrders() throws Exception {
        String productId = createProduct(adminToken(), 2_000, 10);
        String alice = newCustomer().access();
        String bob = newCustomer().access();
        String aliceOrder = placeOrder(alice, productId, 1);

        mvc.perform(get("/api/v1/orders/" + aliceOrder).header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/orders/" + aliceOrder + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/orders/" + aliceOrder + "/payment").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isNotFound());

        String bobsList = mvc.perform(get("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(bobsList).doesNotContain(aliceOrder);

        mvc.perform(get("/api/v1/orders/" + aliceOrder).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk());
    }

    @Test
    void unknownAndInternalPathsAreDeniedByDefault() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/anything")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void adminsCannotDemoteThemselves() throws Exception {
        String admin = adminToken();
        String adminId = userId(admin);
        mvc.perform(patch("/api/v1/admin/users/" + adminId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(JSON).content("{\"role\":\"CUSTOMER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("self_lockout"));
    }
}
