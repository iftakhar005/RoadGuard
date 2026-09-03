package com.roadguard.security;

import com.roadguard.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

// Makes and checks login tokens.
//
// A token is a signed string the client sends back on every later request. It
// carries the username, the user id and the role, so we don't have to hit the
// database just to find out who is calling. The signature is what stops anyone
// editing "role":"DRIVER" into "role":"ADMIN" - change one character and the
// signature no longer matches.
//
// Used by the web side and by the TCP gateway, which authenticates its HELLO
// line with the same token.
@Service
public class JwtService {

    private final SecretKey key;
    private final long expiryMillis;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiry-hours:24}") long expiryHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiryMillis = Duration.ofHours(expiryHours).toMillis();
    }

    public String issue(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expiryMillis)))
                .signWith(key)
                .compact();
    }

    // Empty if the token is expired, tampered with, or just nonsense.
    public Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Optional<String> usernameOf(String token) {
        return parse(token).map(Claims::getSubject);
    }

    public long expirySeconds() {
        return expiryMillis / 1000;
    }
}
