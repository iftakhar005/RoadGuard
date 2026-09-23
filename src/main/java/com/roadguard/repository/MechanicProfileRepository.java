package com.roadguard.repository;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.Specialization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MechanicProfileRepository extends JpaRepository<MechanicProfile, Long> {

    Optional<MechanicProfile> findByUserId(Long userId);

    Optional<MechanicProfile> findByUserUsername(String username);

    List<MechanicProfile> findByStatus(AvailabilityStatus status);

    @EntityGraph(attributePaths = "user")
    List<MechanicProfile> findAllByCurrentLatIsNotNullAndCurrentLngIsNotNull();

    @Query("""
            SELECT m FROM MechanicProfile m
            WHERE m.status = :status
              AND m.currentLat IS NOT NULL
              AND m.currentLng IS NOT NULL
              AND m.currentLat BETWEEN :minLat AND :maxLat
              AND m.currentLng BETWEEN :minLng AND :maxLng
              AND (:skill MEMBER OF m.specializations
                   OR com.roadguard.domain.enums.Specialization.GENERAL MEMBER OF m.specializations)
            """)
    List<MechanicProfile> findCandidatesInBox(@Param("status") AvailabilityStatus status,
                                              @Param("skill") Specialization skill,
                                              @Param("minLat") double minLat,
                                              @Param("maxLat") double maxLat,
                                              @Param("minLng") double minLng,
                                              @Param("maxLng") double maxLng);

    List<MechanicProfile> findByStatusInAndLastHeartbeatBefore(Collection<AvailabilityStatus> statuses,
                                                               Instant cutoff);

    List<MechanicProfile> findByShopNameIsNotNullAndShopLatIsNotNull();

    long countByStatus(AvailabilityStatus status);
}
