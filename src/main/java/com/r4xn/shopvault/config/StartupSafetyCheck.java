package com.r4xn.shopvault.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails fast on configuration that would be insecure in a real deployment: the sample secrets
 * from the dev profile, short webhook secrets, wildcard or plain-http CORS origins.
 */
@Component
class StartupSafetyCheck implements InitializingBean {

    private static final String[] SAMPLE_MARKERS = {"dev-only", "test-only", "change-me", "changeme"};

    private final AppProperties props;
    private final Environment env;

    StartupSafetyCheck(AppProperties props, Environment env) {
        this.props = props;
        this.env = env;
    }

    @Override
    public void afterPropertiesSet() {
        if (props.cors().allowedOrigins().stream().anyMatch(o -> o.contains("*"))) {
            throw new IllegalStateException("Wildcard CORS origins are not allowed; list exact origins");
        }
        if (env.matchesProfiles("dev | test")) {
            return;
        }
        if (isSample(decodedJwtSecret())) {
            throw new IllegalStateException("JWT_SECRET is a sample value; generate one with: openssl rand -base64 48");
        }
        String webhookSecret = props.payments().webhookSecret();
        if (isSample(webhookSecret) || webhookSecret.length() < 32) {
            throw new IllegalStateException(
                    "PAYMENT_WEBHOOK_SECRET must be a real secret of at least 32 characters");
        }
        for (String origin : props.cors().allowedOrigins()) {
            if (!origin.startsWith("https://")) {
                throw new IllegalStateException("CORS origin must use https outside dev: " + origin);
            }
        }
    }

    private String decodedJwtSecret() {
        try {
            return new String(Base64.getDecoder().decode(props.jwt().secret().strip()), StandardCharsets.ISO_8859_1);
        } catch (IllegalArgumentException e) {
            return props.jwt().secret();
        }
    }

    private static boolean isSample(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String marker : SAMPLE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
