package com.r4xn.shopvault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class HardeningTest extends ApiTestSupport {

    @Test
    void responsesCarryStrictSecurityHeaders() throws Exception {
        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists("Permissions-Policy"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        containsString("no-store")));
    }

    @Test
    void hstsIsSentOverHttps() throws Exception {
        mvc.perform(get("/api/v1/products").secure(true))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
    }

    @Test
    void everyResponseHasARequestIdAndUnsafeIdsAreReplaced() throws Exception {
        mvc.perform(get("/api/v1/products").header("X-Request-Id", "client-trace-12345"))
                .andExpect(header().string("X-Request-Id", "client-trace-12345"));
        String replaced = mvc.perform(get("/api/v1/products").header("X-Request-Id", "<script>alert(1)</script>"))
                .andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(replaced).matches("[0-9a-f-]{36}");
    }

    @Test
    void oversizedBodiesAreRejectedBeforeParsing() throws Exception {
        byte[] huge = new byte[2 * 1024 * 1024];
        Arrays.fill(huge, (byte) 'a');
        mvc.perform(post("/api/v1/auth/login").contentType(JSON).content(huge))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("payload_too_large"));
    }

    @Test
    void errorsNeverLeakInternals() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(JSON).content("{\"email\": broken"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Exception", "jackson", "at com.", "trace");

        String notFound = mvc.perform(get("/api/v1/products/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(notFound).doesNotContain("Exception", "select", "Hibernate");
    }

    @Test
    void corsAllowsOnlyConfiguredOrigins() throws Exception {
        mvc.perform(options("/api/v1/products")
                        .header(HttpHeaders.ORIGIN, "https://shop.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://shop.example.com"));
        mvc.perform(options("/api/v1/products")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void searchTextIsALiteralNotAQueryOrPattern() throws Exception {
        for (String q : new String[] {"%", "_", "' OR '1'='1", "\\", "%' ; drop table users; --"}) {
            mvc.perform(get("/api/v1/products").param("q", q))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0));
        }
    }

    @Test
    void paginationAndInputLimitsAreEnforced() throws Exception {
        mvc.perform(get("/api/v1/products").param("size", "1000")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/products").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/products").param("q", "x".repeat(101))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/products").param("sort", "password")).andExpect(status().isBadRequest());
    }

    @Test
    void apiDocsAreNotExposedOutsideDev() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    }
}
