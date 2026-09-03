package com.roadguard.security;

import com.roadguard.domain.User;
import com.roadguard.domain.enums.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

// What Spring Security hands to a controller as "the logged in user".
//
// We keep the database id on here as well as the username, because almost every
// ownership check needs it - only the assigned mechanic may update a job, only
// the driver who created a request may cancel it.
public class AuthUser implements UserDetails {

    private final Long id;
    private final String username;
    private final String passwordHash;
    private final Role role;

    public AuthUser(User user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.passwordHash = user.getPasswordHash();
        this.role = user.getRole();
    }

    public Long getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Spring's hasRole("ADMIN") looks for an authority called ROLE_ADMIN,
        // so the prefix goes on here.
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
