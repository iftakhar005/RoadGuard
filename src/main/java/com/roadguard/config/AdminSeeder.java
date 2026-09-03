package com.roadguard.config;

import com.roadguard.domain.User;
import com.roadguard.domain.enums.Role;
import com.roadguard.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

// Since nobody can register as an admin, the first one has to come from
// somewhere - this creates it at startup if it is not already there.
//
// The credentials come from application-local.properties, which is git-ignored,
// so no admin password ends up on GitHub.
@Configuration
@RequiredArgsConstructor
@Slf4j
public class AdminSeeder {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.username:}")
    private String username;

    @Value("${app.admin.email:}")
    private String email;

    @Value("${app.admin.password:}")
    private String password;

    @Bean
    public ApplicationRunner seedAdmin() {
        return args -> {
            if (username.isBlank() || password.isBlank()) {
                log.warn("No admin configured - set app.admin.username / .email / .password "
                        + "in application-local.properties to get one.");
                return;
            }

            if (users.existsByUsername(username)) {
                log.info("Admin '{}' already exists, leaving it alone.", username);
                return;
            }

            User admin = new User(
                    username,
                    email.isBlank() ? username + "@roadguard.local" : email,
                    passwordEncoder.encode(password),
                    Role.ADMIN);
            users.save(admin);
            log.info("Created admin account '{}'.", username);
        };
    }
}
