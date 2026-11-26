package com.r4xn.shopvault.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    /** Embedded in every access token; bumping it invalidates all tokens issued before. */
    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected User() {}

    public User(String email, String passwordHash, String fullName, Role role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Records a failed password check; returns true when this attempt locked the account. */
    public boolean registerFailedLogin(int maxAttempts, Duration lockDuration, Instant now) {
        failedLoginAttempts++;
        if (failedLoginAttempts >= maxAttempts) {
            failedLoginAttempts = 0;
            lockedUntil = now.plus(lockDuration);
            return true;
        }
        return false;
    }

    public void registerSuccessfulLogin() {
        failedLoginAttempts = 0;
        lockedUntil = null;
    }

    public void unlock() {
        registerSuccessfulLogin();
    }

    /** A real password change: every existing session is invalidated. */
    public void changePasswordHash(String newHash) {
        this.passwordHash = newHash;
        revokeAllTokens();
    }

    /** Re-hash on login (e.g. stronger parameters) without logging the user out. */
    public void upgradePasswordHash(String newHash) {
        this.passwordHash = newHash;
    }

    public void revokeAllTokens() {
        tokenVersion++;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            revokeAllTokens();
        }
    }

    public void setRole(Role role) {
        if (this.role != role) {
            this.role = role;
            revokeAllTokens();
        }
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
