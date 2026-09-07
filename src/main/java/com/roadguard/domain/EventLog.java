package com.roadguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "event_log", indexes = @Index(name = "idx_event_request", columnList = "requestId"))
@Getter
@Setter
@NoArgsConstructor
public class EventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long requestId;

    @Column(nullable = false, length = 40)
    private String type;

    @Column(length = 2000)
    private String payload;

    @Column(nullable = false, updatable = false)
    private Instant timestamp = Instant.now();

    public EventLog(Long requestId, String type, String payload) {
        this.requestId = requestId;
        this.type = type;
        this.payload = payload;
    }
}
