package com.roadguard.service;

import com.roadguard.domain.PasswordResetToken;
import com.roadguard.domain.User;
import com.roadguard.repository.PasswordResetTokenRepository;
import com.roadguard.repository.UserRepository;
import com.roadguard.web.dto.ForgotPasswordRequest;
import com.roadguard.web.dto.ResetPasswordRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final int EXPIRY_MINUTES = 15;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PasswordResetTokenRepository tokenRepo;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    @Transactional
    public Map<String, Object> initiateReset(ForgotPasswordRequest req) {
        String email = req.email().trim().toLowerCase();

        User user = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalArgumentException("No account found with that email address"));

        // Invalidate any older unused tokens for this email
        List<PasswordResetToken> oldTokens = tokenRepo.findByEmailAndUsedFalse(email);
        for (PasswordResetToken old : oldTokens) {
            old.setUsed(true);
        }
        tokenRepo.saveAll(oldTokens);

        // Generate a 6-digit numeric OTP
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(EXPIRY_MINUTES));

        PasswordResetToken token = new PasswordResetToken(email, code, expiresAt);
        tokenRepo.save(token);

        // Send/log verification email
        emailService.sendPasswordResetCode(email, code);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "A 6-digit verification code has been sent to your email.");
        response.put("email", email);
        return response;
    }

    @Transactional
    public Map<String, Object> completeReset(ResetPasswordRequest req) {
        String email = req.email().trim().toLowerCase();

        User user = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalArgumentException("No account found with that email address"));

        PasswordResetToken token = tokenRepo.findTopByEmailAndUsedFalseOrderByCreatedAtDesc(email)
                .orElseThrow(() -> new IllegalArgumentException("No active recovery request found. Please request a new code."));

        if (token.isExpired()) {
            throw new IllegalArgumentException("Verification code has expired. Please request a new one.");
        }

        if (!token.getCode().equals(req.code().trim())) {
            throw new IllegalArgumentException("Invalid verification code. Please check and try again.");
        }

        // Apply new password
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        users.save(user);

        // Mark token as consumed
        token.setUsed(true);
        tokenRepo.save(token);

        return Map.of("message", "Password has been successfully reset! You can now sign in.");
    }
}
