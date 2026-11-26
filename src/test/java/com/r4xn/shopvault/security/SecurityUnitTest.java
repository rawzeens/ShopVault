package com.r4xn.shopvault.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.r4xn.shopvault.common.ApiException;
import org.junit.jupiter.api.Test;

class SecurityUnitTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void passwordPolicyAcceptsLongVariedPassphrases() {
        assertThatCode(() -> policy.check("purple-Elephant-dances-42", "siti@example.com")).doesNotThrowAnyException();
    }

    @Test
    void passwordPolicyRejectsShortCommonRepetitiveOrPersonalPasswords() {
        for (String bad : new String[] {"Sh0rt!", "Password1234", "zzzzzzzzzzzzzzzz", "x".repeat(129),
                "my-name-is-siti-123"}) {
            assertThatThrownBy(() -> policy.check(bad, "siti@example.com"))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("code", "weak_password");
        }
    }

    @Test
    void tokenBucketAllowsBurstThenRefillsOverTime() {
        long minute = 60_000_000_000L;
        TokenBucket bucket = new TokenBucket(3, 0);
        assertThat(bucket.tryConsume(0)).isZero();
        assertThat(bucket.tryConsume(0)).isZero();
        assertThat(bucket.tryConsume(0)).isZero();
        assertThat(bucket.tryConsume(0)).isPositive();
        assertThat(bucket.tryConsume(minute / 3 + 1)).isZero();
        assertThat(bucket.tryConsume(minute / 3 + 2)).isPositive();
    }

    @Test
    void jwtKeysMustBeLongBase64() {
        assertThatThrownBy(() -> JwtConfig.decodeKey("not base64 !!")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtConfig.decodeKey("c2hvcnQ=")).isInstanceOf(IllegalStateException.class);
        assertThat(JwtConfig.decodeKey("a".repeat(44))).hasSizeGreaterThanOrEqualTo(32);
    }
}
