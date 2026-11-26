package com.r4xn.shopvault.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.r4xn.shopvault.config.AppProperties;
import com.r4xn.shopvault.common.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client-IP rate limiting with a strict budget for the credential endpoints (login, register,
 * refresh) and a looser one for the rest of the API. Buckets live in a bounded cache, so a flood
 * of spoofed sources cannot exhaust memory.
 *
 * <p>Behind a reverse proxy, set {@code server.forward-headers-strategy=native} so the client IP
 * comes from the trusted proxy's X-Forwarded-For instead of the proxy itself.
 *
 * <p>This limiter is per instance. When running several replicas, also rate limit at the edge
 * (load balancer / WAF) or move the buckets to Redis.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    static final String AUTH_PREFIX = "/api/v1/auth/";

    private final AppProperties.RateLimit config;
    private final LongSupplier nanoClock;
    private final Cache<String, TokenBucket> buckets = Caffeine.newBuilder()
            .maximumSize(200_000)
            .expireAfterAccess(Duration.ofMinutes(5))
            .build();

    public RateLimitFilter(AppProperties.RateLimit config) {
        this(config, System::nanoTime);
    }

    RateLimitFilter(AppProperties.RateLimit config, LongSupplier nanoClock) {
        this.config = config;
        this.nanoClock = nanoClock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !config.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean credentialEndpoint = request.getRequestURI().startsWith(AUTH_PREFIX);
        int perMinute = credentialEndpoint ? config.authPerMinute() : config.apiPerMinute();
        String key = (credentialEndpoint ? "auth|" : "api|") + request.getRemoteAddr();
        long now = nanoClock.getAsLong();
        TokenBucket bucket = buckets.get(key, k -> new TokenBucket(perMinute, now));
        long retryAfter = bucket.tryConsume(now);
        if (retryAfter > 0) {
            log.warn("Rate limit exceeded for {} on {}", request.getRemoteAddr(), credentialEndpoint ? "auth" : "api");
            response.setHeader("Retry-After", Long.toString(retryAfter));
            ProblemWriter.write(response, HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                    "Too many requests, please slow down and retry later");
            return;
        }
        chain.doFilter(request, response);
    }
}
