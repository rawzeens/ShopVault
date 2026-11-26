package com.r4xn.shopvault.auth;

import com.r4xn.shopvault.audit.AuditService;
import com.r4xn.shopvault.auth.AuthDtos.ChangePasswordRequest;
import com.r4xn.shopvault.auth.AuthDtos.LoginRequest;
import com.r4xn.shopvault.auth.AuthDtos.RegisterRequest;
import com.r4xn.shopvault.auth.AuthDtos.TokenResponse;
import com.r4xn.shopvault.common.ApiException;
import com.r4xn.shopvault.config.AppProperties;
import com.r4xn.shopvault.security.PasswordPolicy;
import com.r4xn.shopvault.security.TokenService;
import com.r4xn.shopvault.user.Role;
import com.r4xn.shopvault.user.User;
import com.r4xn.shopvault.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final TokenService tokenService;
    private final AuditService audit;
    private final AppProperties props;
    private final Clock clock;
    /** Hash checked when the account does not exist, so response time does not reveal that. */
    private final String timingDummyHash;

    public AuthService(
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            TokenService tokenService,
            AuditService audit,
            AppProperties props,
            Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.tokenService = tokenService;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
        this.timingDummyHash = passwordEncoder.encode("timing-equaliser-" + UUID.randomUUID());
    }

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public TokenResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        passwordPolicy.check(request.password(), email);
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("email_taken", "An account with this email already exists");
        }
        User user = users.save(new User(
                email, passwordEncoder.encode(request.password()), request.fullName().strip(), Role.CUSTOMER));
        audit.record(user.getId(), "USER_REGISTERED", "user:" + user.getId(), null);
        return issueTokens(user, UUID.randomUUID());
    }

    /**
     * Failures are committed (noRollbackFor) so the lockout counter and the audit trail survive the
     * exception. Every failure returns the same message, whether the account is unknown, locked,
     * disabled, or the password is wrong.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        Instant now = clock.instant();
        Optional<User> found = users.findByEmailForUpdate(email);
        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), timingDummyHash);
            audit.record(null, "LOGIN_FAILED", "email:" + email, "unknown account");
            throw invalidCredentials();
        }
        User user = found.get();
        String target = "user:" + user.getId();
        if (user.isLocked(now)) {
            passwordEncoder.matches(request.password(), timingDummyHash);
            audit.record(user.getId(), "LOGIN_REJECTED_LOCKED", target, null);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            boolean lockedNow = user.registerFailedLogin(
                    props.security().maxFailedLogins(), props.security().lockDuration(), now);
            audit.record(user.getId(), "LOGIN_FAILED", target, "wrong password");
            if (lockedNow) {
                audit.record(user.getId(), "ACCOUNT_LOCKED", target, "locked until " + user.getLockedUntil());
            }
            throw invalidCredentials();
        }
        if (!user.isEnabled()) {
            audit.record(user.getId(), "LOGIN_REJECTED_DISABLED", target, null);
            throw invalidCredentials();
        }
        user.registerSuccessfulLogin();
        if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
            user.upgradePasswordHash(passwordEncoder.encode(request.password()));
        }
        audit.record(user.getId(), "LOGIN_SUCCEEDED", target, null);
        return issueTokens(user, UUID.randomUUID());
    }

    /** Rotates the refresh token. Re-use of a rotated token revokes the whole login family. */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse refresh(String rawRefreshToken) {
        RefreshToken token = refreshTokens.findByTokenHashForUpdate(TokenService.sha256Hex(rawRefreshToken))
                .orElseThrow(AuthService::invalidRefreshToken);
        Instant now = clock.instant();
        if (token.isRevoked()) {
            refreshTokens.revokeFamily(token.getFamilyId(), now);
            audit.record(token.getUserId(), "REFRESH_TOKEN_REUSE", "user:" + token.getUserId(),
                    "rotated token presented again; session family revoked");
            throw invalidRefreshToken();
        }
        if (token.isExpired(now)) {
            throw invalidRefreshToken();
        }
        User user = users.findById(token.getUserId())
                .filter(User::isEnabled)
                .orElseThrow(AuthService::invalidRefreshToken);
        token.revoke(now);
        return issueTokens(user, token.getFamilyId());
    }

    /** Always succeeds from the caller's view, so it cannot be used to probe tokens. */
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.findByTokenHash(TokenService.sha256Hex(rawRefreshToken)).ifPresent(token -> {
            refreshTokens.revokeFamily(token.getFamilyId(), clock.instant());
            audit.record(token.getUserId(), "LOGOUT", "user:" + token.getUserId(), null);
        });
    }

    @Transactional
    public void logoutEverywhere(UUID userId) {
        User user = users.findByIdForUpdate(userId).orElseThrow(() -> ApiException.notFound("Account"));
        user.revokeAllTokens();
        refreshTokens.revokeAllForUser(userId, clock.instant());
        audit.record(userId, "LOGOUT_ALL", "user:" + userId, null);
    }

    /**
     * Wrong current passwords count toward the lockout; reaching it also ends every session, so a
     * stolen access token cannot be used to brute-force the password.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = users.findByIdForUpdate(userId).orElseThrow(() -> ApiException.notFound("Account"));
        Instant now = clock.instant();
        String target = "user:" + userId;
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            boolean lockedNow = user.registerFailedLogin(
                    props.security().maxFailedLogins(), props.security().lockDuration(), now);
            audit.record(userId, "PASSWORD_CHANGE_FAILED", target, "wrong current password");
            if (lockedNow) {
                user.revokeAllTokens();
                refreshTokens.revokeAllForUser(userId, now);
                audit.record(userId, "ACCOUNT_LOCKED", target, "too many wrong current passwords; sessions revoked");
            }
            throw ApiException.badRequest("invalid_current_password", "The current password is incorrect");
        }
        passwordPolicy.check(request.newPassword(), user.getEmail());
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("password_reused", "The new password must differ from the current one");
        }
        user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
        refreshTokens.revokeAllForUser(userId, now);
        audit.record(userId, "PASSWORD_CHANGED", target, "all sessions revoked");
    }

    /** Daily housekeeping: expired refresh tokens are useless and only grow the table. */
    @Scheduled(cron = "${app.jobs.token-cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void purgeExpiredRefreshTokens() {
        refreshTokens.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(1)));
    }

    private TokenResponse issueTokens(User user, UUID familyId) {
        TokenService.AccessToken access = tokenService.issueAccessToken(user);
        String rawRefresh = tokenService.newRefreshToken();
        Instant now = clock.instant();
        Duration refreshTtl = props.jwt().refreshTokenTtl();
        refreshTokens.save(new RefreshToken(
                user.getId(), TokenService.sha256Hex(rawRefresh), familyId, now.plus(refreshTtl), now));
        return new TokenResponse(access.value(), "Bearer", access.expiresInSeconds(), rawRefresh,
                refreshTtl.toSeconds());
    }

    private static ApiException invalidCredentials() {
        return ApiException.unauthorized("invalid_credentials", INVALID_CREDENTIALS);
    }

    private static ApiException invalidRefreshToken() {
        return ApiException.unauthorized("invalid_refresh_token", "The refresh token is invalid or expired");
    }
}
