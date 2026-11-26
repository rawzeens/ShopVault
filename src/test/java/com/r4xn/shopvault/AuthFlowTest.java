package com.r4xn.shopvault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.r4xn.shopvault.audit.AuditEvent;
import com.r4xn.shopvault.audit.AuditRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

class AuthFlowTest extends ApiTestSupport {

    @Autowired
    AuditRepository auditRepository;

    @Autowired
    JwtEncoder jwtEncoder;

    @Test
    void registeredCustomerCanReadOwnProfile() throws Exception {
        String email = uniqueEmail();
        Tokens tokens = register(email.toUpperCase());

        mvc.perform(get("/api/v1/account/me").header(HttpHeaders.AUTHORIZATION, bearer(tokens.access())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void registrationCannotGrantAdminRole() throws Exception {
        String body = """
                {"email":"%s","password":"%s","fullName":"Mallory","role":"ADMIN","enabled":true}"""
                .formatted(uniqueEmail(), PASSWORD);
        String access = json(mvc.perform(post("/api/v1/auth/register").contentType(JSON).content(body))
                .andExpect(status().isCreated()), "$.accessToken");

        mvc.perform(get("/api/v1/account/me").header(HttpHeaders.AUTHORIZATION, bearer(access)))
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
        mvc.perform(get("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, bearer(access)))
                .andExpect(status().isForbidden());
    }

    @Test
    void weakPasswordsAreRejected() throws Exception {
        String email = uniqueEmail();
        for (String weak : new String[] {"short", "passwordpassword", "aaaaaaaaaaaaaaaa", email.split("@")[0] + "-x1"}) {
            mvc.perform(post("/api/v1/auth/register").contentType(JSON).content("""
                    {"email":"%s","password":"%s","fullName":"Test"}""".formatted(email, weak)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        String email = uniqueEmail();
        register(email);
        mvc.perform(post("/api/v1/auth/register").contentType(JSON).content("""
                {"email":"%s","password":"%s","fullName":"Again"}""".formatted(email.toUpperCase(), PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("email_taken"));
    }

    @Test
    void loginFailuresDoNotRevealWhetherTheAccountExists() throws Exception {
        String email = uniqueEmail();
        register(email);

        String unknown = login(uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String wrong = login(email, "not-the-right-password").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknown).isEqualTo(wrong).contains("invalid_credentials");
    }

    @Test
    void accountLocksAfterRepeatedFailures() throws Exception {
        String email = uniqueEmail();
        String id = userId(register(email).access());

        for (int i = 0; i < 5; i++) {
            login(email, "wrong-password-" + i).andExpect(status().isUnauthorized());
        }
        // Even the correct password is refused while locked, with the same generic answer.
        login(email, PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"));

        List<String> actions = auditRepository.findByTargetOrderByOccurredAtAsc("user:" + id).stream()
                .map(AuditEvent::getAction).toList();
        assertThat(actions).contains("ACCOUNT_LOCKED", "LOGIN_REJECTED_LOCKED");
    }

    @Test
    void adminCanUnlockAnAccount() throws Exception {
        String email = uniqueEmail();
        String id = userId(register(email).access());
        for (int i = 0; i < 5; i++) {
            login(email, "wrong-password-" + i);
        }
        mvc.perform(patch("/api/v1/admin/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(JSON).content("{\"unlock\":true}"))
                .andExpect(status().isOk());
        loginOk(email, PASSWORD);
    }

    @Test
    void refreshTokensRotateAndReuseRevokesTheFamily() throws Exception {
        Tokens first = newCustomer();

        String second = json(refresh(first.refresh()).andExpect(status().isOk()), "$.refreshToken");
        assertThat(second).isNotEqualTo(first.refresh());

        // Replaying the rotated token looks like theft: it fails and kills the newer token too.
        refresh(first.refresh()).andExpect(status().isUnauthorized());
        refresh(second).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        Tokens tokens = newCustomer();
        mvc.perform(post("/api/v1/auth/logout").contentType(JSON)
                        .content("{\"refreshToken\":\"" + tokens.refresh() + "\"}"))
                .andExpect(status().isNoContent());
        refresh(tokens.refresh()).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutEverywhereInvalidatesExistingAccessTokens() throws Exception {
        Tokens tokens = newCustomer();
        mvc.perform(post("/api/v1/auth/logout-all").header(HttpHeaders.AUTHORIZATION, bearer(tokens.access())))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/account/me").header(HttpHeaders.AUTHORIZATION, bearer(tokens.access())))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""));
        refresh(tokens.refresh()).andExpect(status().isUnauthorized());
    }

    @Test
    void changingPasswordEndsAllSessions() throws Exception {
        String email = uniqueEmail();
        Tokens tokens = register(email);
        String newPassword = "another-Strong-passphrase-7";

        mvc.perform(post("/api/v1/account/password").header(HttpHeaders.AUTHORIZATION, bearer(tokens.access()))
                        .contentType(JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(PASSWORD, newPassword)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/account/me").header(HttpHeaders.AUTHORIZATION, bearer(tokens.access())))
                .andExpect(status().isUnauthorized());
        login(email, PASSWORD).andExpect(status().isUnauthorized());
        loginOk(email, newPassword);
    }

    @Test
    void changingPasswordRequiresTheCurrentPassword() throws Exception {
        Tokens tokens = newCustomer();
        mvc.perform(post("/api/v1/account/password").header(HttpHeaders.AUTHORIZATION, bearer(tokens.access()))
                        .contentType(JSON)
                        .content("{\"currentPassword\":\"guess-guess-guess\",\"newPassword\":\"another-Strong-passphrase-7\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_current_password"));
    }

    @Test
    void tamperedAndUnsignedTokensAreRejected() throws Exception {
        String access = newCustomer().access();
        String[] parts = access.split("\\.");

        String flipped = parts[0] + "." + parts[1] + "." + (parts[2].charAt(0) == 'A' ? 'B' : 'A') + parts[2].substring(1);
        String noneHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String unsigned = noneHeader + "." + parts[1] + ".";
        String elevatedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                        .replace("CUSTOMER", "ADMIN").getBytes(StandardCharsets.UTF_8));
        String elevated = parts[0] + "." + elevatedPayload + "." + parts[2];

        for (String bad : new String[] {flipped, unsigned, elevated, "not-a-jwt"}) {
            mvc.perform(get("/api/v1/account/me").header(HttpHeaders.AUTHORIZATION, bearer(bad)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void tokensForAnotherAudienceOrIssuerAreRejected() throws Exception {
        String id = userId(newCustomer().access());
        Instant now = Instant.now();
        for (String[] issuerAudience : new String[][] {{"shopvault", "other-api"}, {"evil-issuer", "shopvault-api"}}) {
            JwtClaimsSet claims = JwtClaimsSet.builder()
                    .issuer(issuerAudience[0]).audience(List.of(issuerAudience[1])).subject(id)
                    .issuedAt(now).expiresAt(now.plusSeconds(300))
                    .claim("roles", List.of("CUSTOMER")).claim("tv", 0).build();
            String token = jwtEncoder.encode(JwtEncoderParameters.from(
                    JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
            mvc.perform(get("/api/v1/account/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void disabledUsersLoseAccessImmediately() throws Exception {
        String email = uniqueEmail();
        Tokens tokens = register(email);
        String id = userId(tokens.access());

        mvc.perform(patch("/api/v1/admin/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/account/me").header(HttpHeaders.AUTHORIZATION, bearer(tokens.access())))
                .andExpect(status().isUnauthorized());
        login(email, PASSWORD).andExpect(status().isUnauthorized());
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").contentType(JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
    }
}
