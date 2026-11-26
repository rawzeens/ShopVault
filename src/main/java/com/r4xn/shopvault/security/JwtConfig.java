package com.r4xn.shopvault.security;

import com.r4xn.shopvault.config.AppProperties;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Short-lived HS256 access tokens. The decoder pins the algorithm (no "alg: none" or algorithm
 * confusion), checks issuer, audience, expiry, typ, and that the user is still active and has
 * not revoked their sessions.
 */
@Configuration
public class JwtConfig {

    public static final String ROLES_CLAIM = "roles";
    public static final String TOKEN_VERSION_CLAIM = "tv";
    static final int MIN_KEY_BYTES = 32;

    @Bean
    SecretKey jwtSigningKey(AppProperties props) {
        return new SecretKeySpec(decodeKey(props.jwt().secret()), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSigningKey).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, AppProperties props, TokenStatusValidator statusValidator) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        String audience = props.jwt().audience();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.jwt().issuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)),
                statusValidator);
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    static byte[] decodeKey(String secret) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "app.jwt.secret (JWT_SECRET) must be base64. Generate one with: openssl rand -base64 48");
        }
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("app.jwt.secret (JWT_SECRET) must decode to at least "
                    + MIN_KEY_BYTES + " bytes. Generate one with: openssl rand -base64 48");
        }
        return bytes;
    }
}
