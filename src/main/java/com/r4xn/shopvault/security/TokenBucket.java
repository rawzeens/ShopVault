package com.r4xn.shopvault.security;

/** Classic token bucket: {@code capacity} requests at once, refilled continuously over a minute. */
final class TokenBucket {

    private static final long NANOS_PER_MINUTE = 60_000_000_000L;

    private final double capacity;
    private final double refillPerNano;
    private double tokens;
    private long lastRefill;

    TokenBucket(int perMinute, long nowNanos) {
        this.capacity = perMinute;
        this.refillPerNano = perMinute / (double) NANOS_PER_MINUTE;
        this.tokens = perMinute;
        this.lastRefill = nowNanos;
    }

    /** Takes one token. Returns 0 when allowed, otherwise the seconds until the next token. */
    synchronized long tryConsume(long nowNanos) {
        long elapsed = Math.max(0, nowNanos - lastRefill);
        tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
        lastRefill = nowNanos;
        if (tokens >= 1) {
            tokens -= 1;
            return 0;
        }
        double missingNanos = (1 - tokens) / refillPerNano;
        return Math.max(1, (long) Math.ceil(missingNanos / 1_000_000_000d));
    }
}
