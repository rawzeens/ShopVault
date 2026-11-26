package com.r4xn.shopvault.payment;

import com.r4xn.shopvault.common.ApiException;
import com.r4xn.shopvault.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Verifies {@code X-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}.
 * The timestamp is signed too and must be recent, which stops replay of captured webhooks; the
 * comparison is constant-time.
 */
@Component
public class WebhookSignatureVerifier {

    public static final String HEADER = "X-Signature";

    private final byte[] secret;
    private final Duration tolerance;
    private final Clock clock;

    public WebhookSignatureVerifier(AppProperties props, Clock clock) {
        this.secret = props.payments().webhookSecret().getBytes(StandardCharsets.UTF_8);
        this.tolerance = props.payments().webhookTolerance();
        this.clock = clock;
    }

    public void verify(String header, byte[] body) {
        if (header == null || header.length() > 512) {
            throw invalid();
        }
        String timestamp = null;
        String signature = null;
        for (String part : header.split(",")) {
            String[] kv = part.strip().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                timestamp = kv[1];
            } else if (kv[0].equals("v1")) {
                signature = kv[1].toLowerCase(Locale.ROOT);
            }
        }
        if (timestamp == null || signature == null || !timestamp.matches("^[0-9]{1,12}$")) {
            throw invalid();
        }
        long ageSeconds = Math.abs(clock.instant().getEpochSecond() - Long.parseLong(timestamp));
        if (ageSeconds > tolerance.toSeconds()) {
            throw invalid();
        }
        byte[] expected = sign(timestamp, body).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII))) {
            throw invalid();
        }
    }

    /** Also used by tests and scripts/send-test-webhook.sh produces the same value with openssl. */
    public String sign(String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static ApiException invalid() {
        return ApiException.unauthorized("invalid_signature", "The webhook signature is missing or invalid");
    }
}
