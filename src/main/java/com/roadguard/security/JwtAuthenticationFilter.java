package com.roadguard.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// Runs before every request. If there's a valid token in the Authorization
// header, we tell Spring Security who this is; otherwise we just carry on and
// let the rules decide whether the endpoint needed a login anyway.
//
// Note it never rejects anything itself - a bad token means "not logged in",
// not "error". Deciding what is allowed is the config's job, not this filter's.
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwt;
    private final AppUserDetailsService userDetails;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        // Already authenticated earlier in the chain - leave it alone.
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        jwt.usernameOf(header.substring(PREFIX.length()))
                .ifPresent(username -> {
                    try {
                        AuthUser user = userDetails.loadUserByUsername(username);
                        var auth = new UsernamePasswordAuthenticationToken(
                                user, null, user.getAuthorities());
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    } catch (UsernameNotFoundException e) {
                        // Token is signed correctly but the account is gone.
                        // Treat it as not logged in.
                    }
                });

        chain.doFilter(request, response);
    }
}
