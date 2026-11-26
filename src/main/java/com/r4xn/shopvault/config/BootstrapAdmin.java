package com.r4xn.shopvault.config;

import com.r4xn.shopvault.auth.AuthService;
import com.r4xn.shopvault.security.PasswordPolicy;
import com.r4xn.shopvault.user.Role;
import com.r4xn.shopvault.user.User;
import com.r4xn.shopvault.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator from BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD, only
 * while no admin exists. There is no public way to become admin. Remove the variables after the
 * first start.
 */
@Component
@Order(0)
class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final AppProperties props;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;

    BootstrapAdmin(AppProperties props, UserRepository users, PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy) {
        this.props = props;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String email = props.bootstrap().adminEmail();
        String password = props.bootstrap().adminPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        if (users.existsByRole(Role.ADMIN)) {
            log.info("An admin already exists; BOOTSTRAP_ADMIN_* can be removed from the environment");
            return;
        }
        String normalized = AuthService.normalizeEmail(email);
        passwordPolicy.check(password, normalized);
        if (users.existsByEmail(normalized)) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_EMAIL belongs to an existing non-admin account");
        }
        users.save(new User(normalized, passwordEncoder.encode(password), "Administrator", Role.ADMIN));
        log.warn("Bootstrap admin account created for {}. Remove BOOTSTRAP_ADMIN_PASSWORD from the environment.",
                normalized);
    }
}
