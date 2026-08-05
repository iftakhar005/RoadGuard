package com.roadguard.repository;

import com.roadguard.domain.EventLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventLogRepository extends JpaRepository<EventLog, Long> {

    // The whole story of one request in order. Feeds the replay endpoint.
    List<EventLog> findByRequestIdOrderByTimestampAsc(Long requestId);

    List<EventLog> findByRequestIdAndTypeOrderByTimestampAsc(Long requestId, String type);
}
