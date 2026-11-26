package com.r4xn.shopvault.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

public final class CurrentUser {

    private CurrentUser() {}

    /** The authenticated user's id; the subject was already validated by {@link TokenStatusValidator}. */
    public static UUID id(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
