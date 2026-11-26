package com.r4xn.shopvault.user;

import com.r4xn.shopvault.auth.AuthDtos.ChangePasswordRequest;
import com.r4xn.shopvault.auth.AuthService;
import com.r4xn.shopvault.common.ApiException;
import com.r4xn.shopvault.security.CurrentUser;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final UserRepository users;
    private final AuthService authService;

    public AccountController(UserRepository users, AuthService authService) {
        this.users = users;
        this.authService = authService;
    }

    @GetMapping("/me")
    public AccountResponse me(@AuthenticationPrincipal Jwt jwt) {
        return users.findById(CurrentUser.id(jwt))
                .map(AccountResponse::from)
                .orElseThrow(() -> ApiException.notFound("Account"));
    }

    /** Changing the password signs out every device, including this one. */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(CurrentUser.id(jwt), request);
    }

    public record AccountResponse(UUID id, String email, String fullName, Role role, Instant createdAt) {
        static AccountResponse from(User u) {
            return new AccountResponse(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.getCreatedAt());
        }
    }
}
