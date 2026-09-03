package com.roadguard.security;

import com.roadguard.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

// Tells Spring Security how to look a user up. It only knows about usernames,
// so this is the bridge to our own table.
@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    @Override
    public AuthUser loadUserByUsername(String username) {
        return users.findByUsername(username)
                .map(AuthUser::new)
                .orElseThrow(() -> new UsernameNotFoundException("No user called " + username));
    }
}
