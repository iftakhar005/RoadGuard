package com.roadguard.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    // BCrypt. Passwords are never stored in a form we could read back - we only
    // ever hash the attempt and compare hashes.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // No browser forms and no cookies, so there is no CSRF to protect
                // against - the token has to be attached by our own JavaScript.
                .csrf(csrf -> csrf.disable())

                // Nothing is remembered between requests. Every call carries its
                // own token. This is what lets the TCP gateway and the web side
                // share one auth story.
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        // The pages themselves are just empty shells - every bit
                        // of real data on them is fetched from the API with a
                        // token, and those calls are still protected.
                        .requestMatchers("/", "/*.html", "/css/**", "/js/**", "/favicon.ico").permitAll()
                        .requestMatchers("/h2-console/**").permitAll()
                        // Spring forwards unhandled errors here. If it needed a
                        // login, every 404 and 403 would come back as a 401
                        // instead of the real status.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())

                // Without a token, return 401 rather than redirecting to a login
                // page - the client is JavaScript, not a browser following links.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)

                // The H2 console runs in a frame, which is blocked by default.
                .headers(h -> h.frameOptions(f -> f.sameOrigin()));

        return http.build();
    }
}
