package com.roadguard.repository;

import com.roadguard.domain.User;
import com.roadguard.domain.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

// Spring builds the implementation of this at startup. We only declare what we
// want; the method names are the query.
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    // Used at registration so we can reject a duplicate before trying to insert.
    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    List<User> findByRole(Role role);
}
