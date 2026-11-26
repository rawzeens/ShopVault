package com.r4xn.shopvault.config;

import java.time.Clock;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@EnableScheduling
public class CoreConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Argon2id with the OWASP minimum profile (19 MiB memory, 2 iterations, 1 lane). Hashes are
     * stored with an {@code {argon2}} prefix so the algorithm can be upgraded later without a
     * migration; BCrypt is accepted only for verifying legacy hashes.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        PasswordEncoder argon2 = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
        return new DelegatingPasswordEncoder("argon2", Map.of(
                "argon2", argon2,
                "bcrypt", new BCryptPasswordEncoder()));
    }
}
