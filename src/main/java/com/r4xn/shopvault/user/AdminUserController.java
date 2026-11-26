package com.r4xn.shopvault.user;

import com.r4xn.shopvault.audit.AuditService;
import com.r4xn.shopvault.auth.RefreshTokenRepository;
import com.r4xn.shopvault.common.ApiException;
import com.r4xn.shopvault.common.PageResponse;
import com.r4xn.shopvault.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AuditService audit;
    private final Clock clock;

    public AdminUserController(
            UserRepository users, RefreshTokenRepository refreshTokens, AuditService audit, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping
    public PageResponse<AdminUserResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        var result = users.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(result, AdminUserResponse::from);
    }

    /**
     * Enable/disable, change role, or clear a lockout. Disabling or changing the role revokes the
     * user's sessions. Admins cannot disable or demote themselves (avoids locking out the shop).
     */
    @PatchMapping("/{id}")
    @Transactional
    public AdminUserResponse update(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        UUID actor = CurrentUser.id(jwt);
        User user = users.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("User"));
        boolean self = actor.equals(id);
        if (self && (Boolean.FALSE.equals(request.enabled()) || (request.role() != null && request.role() != Role.ADMIN))) {
            throw ApiException.conflict("self_lockout", "You cannot disable or demote your own account");
        }
        List<String> changes = new ArrayList<>();
        if (request.enabled() != null && request.enabled() != user.isEnabled()) {
            user.setEnabled(request.enabled());
            changes.add("enabled=" + request.enabled());
        }
        if (request.role() != null && request.role() != user.getRole()) {
            user.setRole(request.role());
            changes.add("role=" + request.role());
        }
        if (Boolean.TRUE.equals(request.unlock())) {
            user.unlock();
            changes.add("unlocked");
        }
        if (changes.stream().anyMatch(c -> c.startsWith("enabled") || c.startsWith("role"))) {
            refreshTokens.revokeAllForUser(id, clock.instant());
        }
        if (!changes.isEmpty()) {
            audit.record(actor, "ADMIN_USER_UPDATED", "user:" + id, String.join(", ", changes));
        }
        return AdminUserResponse.from(user);
    }

    public record UpdateUserRequest(Boolean enabled, Role role, Boolean unlock) {}

    public record AdminUserResponse(
            UUID id, String email, String fullName, Role role, boolean enabled, Instant lockedUntil, Instant createdAt) {
        static AdminUserResponse from(User u) {
            return new AdminUserResponse(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.isEnabled(),
                    u.getLockedUntil(), u.getCreatedAt());
        }
    }
}
