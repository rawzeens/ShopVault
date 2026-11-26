package com.r4xn.shopvault.security;

import com.r4xn.shopvault.common.ApiException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * NIST SP 800-63B style: long passwords, no composition rules, block known-breached and trivially
 * guessable values. The 128 character cap bounds the hashing cost.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    private final Set<String> blocked;

    public PasswordPolicy() {
        this.blocked = loadBlocklist();
    }

    public void check(String password, String email) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw weak("Password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.length() > MAX_LENGTH) {
            throw weak("Password must be at most " + MAX_LENGTH + " characters");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (blocked.contains(lower)) {
            throw weak("This password is too common, please choose another one");
        }
        if (password.chars().distinct().count() < 5) {
            throw weak("Password is too simple, use more varied characters");
        }
        if (email != null) {
            String local = email.toLowerCase(Locale.ROOT).split("@", 2)[0];
            if (local.length() >= 4 && lower.contains(local)) {
                throw weak("Password must not contain your email address");
            }
        }
    }

    private static ApiException weak(String message) {
        return ApiException.badRequest("weak_password", message);
    }

    private static Set<String> loadBlocklist() {
        try (InputStream in = PasswordPolicy.class.getResourceAsStream("/security/common-passwords.txt")) {
            if (in == null) {
                throw new IllegalStateException("security/common-passwords.txt is missing from the classpath");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .map(line -> line.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
