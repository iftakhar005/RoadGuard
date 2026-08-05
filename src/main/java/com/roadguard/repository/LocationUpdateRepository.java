package com.roadguard.repository;

import com.roadguard.domain.LocationUpdate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface LocationUpdateRepository extends JpaRepository<LocationUpdate, Long> {

    // The trail for one job, oldest first - this is what the replay screen animates.
    List<LocationUpdate> findByRequestIdOrderByRecordedAtAsc(Long requestId);

    List<LocationUpdate> findByMechanicIdAndRecordedAtAfterOrderByRecordedAtAsc(Long mechanicId,
                                                                                Instant since);

    void deleteByRecordedAtBefore(Instant cutoff);
}
