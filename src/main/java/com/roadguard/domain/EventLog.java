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

// Append-only trail of everything that happened to a request. The same entries
// also get written to logs/request-{id}.jsonl on disk - the file is the real
// source of truth and this table is the queryable copy.
//
// Deliberately not linked to ServiceRequest with a foreign key: these are meant
// to survive independently of the row they describe.
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

    // What happened, e.g. STATUS_CHANGED, OFFER_SENT, ACCEPT_WON, ACCEPT_LOST.
    @Column(nullable = false, length = 40)
    private String type;

    // Whatever detail goes with it, as a JSON string.
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
