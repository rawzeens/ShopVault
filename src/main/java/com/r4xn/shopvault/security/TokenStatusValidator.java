package com.r4xn.shopvault.security;

import com.r4xn.shopvault.user.UserRepository;
import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Makes stateless tokens revocable: a token is rejected once the user is disabled, changes their
 * password, logs out everywhere, or has their role changed (each bumps the user's token version).
 */
@Component
public class TokenStatusValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2TokenValidatorResult REVOKED = OAuth2TokenValidatorResult.failure(
            new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The token has been revoked", null));

    private final UserRepository users;

    public TokenStatusValidator(UserRepository users) {
        this.users = users;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (RuntimeException e) {
            return REVOKED;
        }
        Object claim = jwt.getClaims().get(JwtConfig.TOKEN_VERSION_CLAIM);
        if (!(claim instanceof Number version)) {
            return REVOKED;
        }
        return users.findById(userId)
                .filter(u -> u.isEnabled() && u.getTokenVersion() == version.intValue())
                .map(u -> OAuth2TokenValidatorResult.success())
                .orElse(REVOKED);
    }
}
