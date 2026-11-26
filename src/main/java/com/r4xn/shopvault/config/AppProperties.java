package com.r4xn.shopvault.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Every tunable of the server in one typed, validated place. The application refuses to start
 * when a required value (secrets, database) is missing or invalid.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Security security,
        @Valid @NotNull Shop shop,
        @Valid @NotNull Payments payments,
        @Valid @NotNull Cors cors,
        @Valid @NotNull Bootstrap bootstrap) {

    public record Jwt(
            @NotBlank String issuer,
            @NotBlank String audience,
            /* Base64-encoded HMAC key, at least 32 bytes once decoded. */
            @NotBlank String secret,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration refreshTokenTtl) {}

    public record Security(
            @Min(1) int maxFailedLogins,
            @NotNull Duration lockDuration,
            @Positive long maxRequestBytes,
            @Valid @NotNull RateLimit rateLimit) {}

    public record RateLimit(boolean enabled, @Min(1) int authPerMinute, @Min(1) int apiPerMinute) {}

    public record Shop(
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @PositiveOrZero long shippingFlatCents,
            @PositiveOrZero long freeShippingThresholdCents,
            @NotNull Duration paymentTimeout,
            @Min(1) int maxCartLines,
            @Min(1) int maxQuantityPerLine) {}

    public record Payments(
            @NotBlank String webhookSecret,
            @NotNull Duration webhookTolerance,
            @NotBlank String checkoutBaseUrl) {}

    public record Cors(List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null
                    ? List.of()
                    : allowedOrigins.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        }
    }

    public record Bootstrap(String adminEmail, String adminPassword) {}
}
