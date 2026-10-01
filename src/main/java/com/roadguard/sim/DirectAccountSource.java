package com.roadguard.sim;

import com.roadguard.domain.enums.Role;
import com.roadguard.domain.enums.Specialization;
import com.roadguard.service.AuthService;
import com.roadguard.web.dto.AuthResponse;
import com.roadguard.web.dto.LoginRequest;
import com.roadguard.web.dto.RegisterRequest;

import java.util.Set;

public class DirectAccountSource implements AccountSource {

    private final AuthService auth;

    public DirectAccountSource(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public Account getAccount(int index) {
        String username = SIM_PREFIX + index;
        String email = SIM_PREFIX + index + "@fleet.local";
        try {
            AuthResponse res = auth.register(new RegisterRequest(
                    username,
                    email,
                    PASSWORD,
                    Role.MECHANIC,
                    "0170000%04d".formatted(index),
                    Set.of(Specialization.values())
            ));
            return new Account(res.userId(), res.username(), res.token());
        } catch (IllegalArgumentException e) {
            AuthResponse res = auth.login(new LoginRequest(email, PASSWORD));
            return new Account(res.userId(), res.username(), res.token());
        }
    }
}
