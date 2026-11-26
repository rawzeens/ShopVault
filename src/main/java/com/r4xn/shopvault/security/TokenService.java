package com.r4xn.shopvault.security;

import com.r4xn.shopvault.config.AppProperties;
import com.r4xn.shopvault.user.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final AppProperties.Jwt config;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TokenService(JwtEncoder encoder, AppProperties props, Clock clock) {
        this.encoder = encoder;
        this.config = props.jwt();
        this.clock = clock;
    }

    public AccessToken issueAccessToken(User user) {
        Instant now = clock.instant();
        Duration ttl = config.accessTokenTtl();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(config.issuer())
                .audience(List.of(config.audience()))
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(now.plus(ttl))
                .claim(JwtConfig.ROLES_CLAIM, List.of(user.getRole().name()))
                .claim(JwtConfig.TOKEN_VERSION_CLAIM, user.getTokenVersion())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, ttl.toSeconds());
    }

    /** 256 bits from a CSPRNG; only its SHA-256 hash is ever stored. */
    public String newRefreshToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record AccessToken(String value, long expiresInSeconds) {}
}
