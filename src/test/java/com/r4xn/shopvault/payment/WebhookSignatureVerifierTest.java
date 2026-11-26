package com.r4xn.shopvault.payment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.r4xn.shopvault.common.ApiException;
import com.r4xn.shopvault.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    private static final byte[] BODY = "{\"eventId\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);

    private static WebhookSignatureVerifier verifier(String secret) {
        AppProperties.Payments payments = new AppProperties.Payments(secret, Duration.ofMinutes(5), "https://pay");
        AppProperties props = new AppProperties(null, null, null, payments, null, null);
        return new WebhookSignatureVerifier(props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void acceptsAFreshCorrectSignature() {
        WebhookSignatureVerifier v = verifier("s3cret-s3cret-s3cret-s3cret-s3cret");
        String t = Long.toString(NOW.getEpochSecond() - 30);
        assertThatCode(() -> v.verify("t=" + t + ",v1=" + v.sign(t, BODY), BODY)).doesNotThrowAnyException();
        assertThatCode(() -> v.verify(" v1=" + v.sign(t, BODY).toUpperCase() + " , t=" + t, BODY))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWrongSecretTamperedBodyStaleOrFutureTimestampsAndGarbage() {
        WebhookSignatureVerifier v = verifier("s3cret-s3cret-s3cret-s3cret-s3cret");
        WebhookSignatureVerifier attacker = verifier("guessed-guessed-guessed-guessed!!");
        String t = Long.toString(NOW.getEpochSecond());
        String old = Long.toString(NOW.getEpochSecond() - 301);
        String future = Long.toString(NOW.getEpochSecond() + 301);
        byte[] tampered = "{\"eventId\":\"evt_2\"}".getBytes(StandardCharsets.UTF_8);

        String[] headers = {
            "t=" + t + ",v1=" + attacker.sign(t, BODY),
            "t=" + old + ",v1=" + v.sign(old, BODY),
            "t=" + future + ",v1=" + v.sign(future, BODY),
            "v1=" + v.sign(t, BODY),
            "t=" + t,
            "t=abc,v1=" + v.sign(t, BODY),
            "",
        };
        for (String header : headers) {
            assertThatThrownBy(() -> v.verify(header, BODY)).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> v.verify("t=" + t + ",v1=" + v.sign(t, BODY), tampered))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> v.verify(null, BODY)).isInstanceOf(ApiException.class);
    }
}
