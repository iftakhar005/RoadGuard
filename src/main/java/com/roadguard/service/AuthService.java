package com.roadguard.service;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.domain.enums.Specialization;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.repository.UserRepository;
import com.roadguard.security.JwtService;
import com.roadguard.web.dto.AuthResponse;
import com.roadguard.web.dto.LoginRequest;
import com.roadguard.web.dto.RegisterRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository users;
    private final MechanicProfileRepository mechanics;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;

    @Transactional
    public AuthResponse register(RegisterRequest req) {

        if (req.role() == Role.ADMIN) {
            throw new IllegalArgumentException("Admin accounts cannot be created here");
        }
        if (users.existsByUsername(req.username())) {
            throw new IllegalArgumentException("That username is taken");
        }
        if (users.existsByEmail(req.email())) {
            throw new IllegalArgumentException("That email is already registered");
        }

        User user = new User(
                req.username(),
                req.email(),
                passwordEncoder.encode(req.password()),
                req.role());
        user.setPhone(req.phone());
        users.save(user);

        if (req.role() == Role.MECHANIC) {
            MechanicProfile profile = new MechanicProfile(user);
            Set<Specialization> skills = req.specializations();
            if (skills == null || skills.isEmpty()) {

                profile.setSpecializations(Set.of(Specialization.GENERAL));
            } else {
                profile.setSpecializations(skills);
            }
            profile.setStatus(AvailabilityStatus.OFFLINE);
            mechanics.save(profile);
        }

        return tokenFor(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        String identifier = req.email().trim();
        User user = users.findByEmailIgnoreCase(identifier)
                .or(() -> users.findByUsernameIgnoreCase(identifier))
                .orElseThrow(() -> new BadCredentialsException("Wrong email or password"));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Wrong email or password");
        }

        return tokenFor(user);
    }

    private AuthResponse tokenFor(User user) {
        return new AuthResponse(
                jwt.issue(user),
                jwt.expirySeconds(),
                user.getId(),
                user.getUsername(),
                user.getRole());
    }
}
