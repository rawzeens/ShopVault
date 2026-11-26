package com.r4xn.shopvault;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
    "app.security.rate-limit.enabled=true",
    "app.security.rate-limit.auth-per-minute=3",
    "app.security.rate-limit.api-per-minute=1000"
})
class RateLimitTest extends ApiTestSupport {

    @Test
    void credentialEndpointsAreThrottledPerClient() throws Exception {
        String email = uniqueEmail();
        for (int i = 0; i < 3; i++) {
            login(email, "wrong-password").andExpect(status().isUnauthorized());
        }
        login(email, "wrong-password")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("rate_limited"));

        // The general API budget is separate, so browsing still works.
        mvc.perform(get("/api/v1/products")).andExpect(status().isOk());
    }
}
