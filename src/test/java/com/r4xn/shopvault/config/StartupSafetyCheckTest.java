package com.r4xn.shopvault.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class StartupSafetyCheckTest {

    private static final String REAL_JWT = Base64.getEncoder()
            .encodeToString("9f8e7d6c5b4a39281706f5e4d3c2b1a0-real-key".getBytes(StandardCharsets.UTF_8));
    private static final String REAL_WEBHOOK = "whsec_4f9a1c7e2b8d6a0f3e5c9b7d1a2f4e6c";

    private static AppProperties props(String jwtSecret, String webhookSecret, List<String> origins) {
        return new AppProperties(
                new AppProperties.Jwt("shopvault", "shopvault-api", jwtSecret, Duration.ofMinutes(15), Duration.ofDays(14)),
                null, null,
                new AppProperties.Payments(webhookSecret, Duration.ofMinutes(5), "https://pay.example.com"),
                new AppProperties.Cors(origins),
                new AppProperties.Bootstrap(null, null));
    }

    private static void check(AppProperties props, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        new StartupSafetyCheck(props, env).afterPropertiesSet();
    }

    @Test
    void productionWithRealSecretsStarts() {
        assertThatCode(() -> check(props(REAL_JWT, REAL_WEBHOOK, List.of("https://shop.example.com")), "prod"))
                .doesNotThrowAnyException();
    }

    @Test
    void sampleSecretsShortSecretsAndUnsafeOriginsAreRefusedOutsideDev() {
        String devJwt = Base64.getEncoder().encodeToString(
                "dev-only-jwt-secret-never-use-this-outside-your-laptop".getBytes(StandardCharsets.UTF_8));
        List<AppProperties> unsafe = List.of(
                props(devJwt, REAL_WEBHOOK, List.of()),
                props(REAL_JWT, "dev-only-webhook-secret", List.of()),
                props(REAL_JWT, "too-short", List.of()),
                props(REAL_JWT, REAL_WEBHOOK, List.of("http://shop.example.com")),
                props(REAL_JWT, REAL_WEBHOOK, List.of("*")));
        for (AppProperties p : unsafe) {
            assertThatThrownBy(() -> check(p, "prod")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void wildcardOriginsAreRefusedEvenInDev() {
        assertThatThrownBy(() -> check(props(REAL_JWT, REAL_WEBHOOK, List.of("https://*.example.com")), "dev"))
                .isInstanceOf(IllegalStateException.class);
    }
}
