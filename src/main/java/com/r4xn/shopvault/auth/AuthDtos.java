package com.r4xn.shopvault.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request/response bodies for authentication. Requests carry only the fields a client may set,
 * so extra JSON properties (e.g. "role": "ADMIN") have nowhere to bind.
 */
public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotNull @Size(min = 12, max = 128) String password,
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[^\\p{Cntrl}]+$", message = "must not contain control characters")
                    String fullName) {}

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotNull @Size(min = 1, max = 128) String password) {}

    public record RefreshRequest(@NotBlank @Size(max = 128) String refreshToken) {}

    public record ChangePasswordRequest(
            @NotNull @Size(min = 1, max = 128) String currentPassword,
            @NotNull @Size(min = 12, max = 128) String newPassword) {}

    public record TokenResponse(
            String accessToken, String tokenType, long expiresIn, String refreshToken, long refreshExpiresIn) {}
}
