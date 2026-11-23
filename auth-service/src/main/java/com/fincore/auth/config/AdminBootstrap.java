package com.fincore.auth.config;

import com.fincore.auth.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Creates the first ADMIN user from configuration. Supply ADMIN_PASSWORD outside local development. */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AuthService authService;
    private final String username;
    private final String email;
    private final String password;

    public AdminBootstrap(
            AuthService authService,
            @Value("${fincore.bootstrap-admin.username:}") String username,
            @Value("${fincore.bootstrap-admin.email:admin@fincore.local}") String email,
            @Value("${fincore.bootstrap-admin.password:}") String password) {
        this.authService = authService;
        this.username = username;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isBlank() || password.isBlank()) {
            return;
        }
        authService.createAdminIfAbsent(username.toLowerCase(), email.toLowerCase(), password);
        log.info("Admin user '{}' is available", username);
    }
}
