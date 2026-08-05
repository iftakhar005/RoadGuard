package com.roadguard.repository;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.Specialization;
import org.springframework.data.jpa.repository.JpaRepository;
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

    // The first half of matching.
    //
    // The exact distance needs the haversine formula, which is not something SQL
    // can do here, so that part happens in Java. But pulling every online
    // mechanic in the country back just to throw most away would be wasteful, so
    // this narrows to a rough box around the request first. Java then does the
    // real distance check and the ranking on whatever survives.
    //
    // A GENERAL mechanic is allowed through as a fallback even when a specialist
    // was asked for - better a generalist turns up than nobody.
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

    // What the heartbeat reaper runs on its timer: anyone who is supposed to be
    // connected but has gone quiet for longer than the timeout.
    List<MechanicProfile> findByStatusInAndLastHeartbeatBefore(Collection<AvailabilityStatus> statuses,
                                                               Instant cutoff);

    long countByStatus(AvailabilityStatus status);
}
