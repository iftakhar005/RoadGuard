package com.roadguard.domain;

import com.roadguard.domain.enums.IssueType;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.domain.enums.Severity;
import com.roadguard.domain.enums.Specialization;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

// One SOS from a driver. This is the row every mechanic races to accept,
// so it's the one that needs the most care around concurrent updates.
@Entity
@Table(name = "service_requests")
@Getter
@Setter
@NoArgsConstructor
public class ServiceRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    private User driver;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IssueType issueType;

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private double originLat;

    @Column(nullable = false)
    private double originLng;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequestStatus status = RequestStatus.CREATED;

    // Set by the AI photo check, or worked out from issueType if there's no photo.
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Specialization requiredSpecialization;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Severity severity = Severity.MEDIUM;

    // Null until somebody wins the race. Only ever set from null - that guard
    // is a second line of defence behind the lock.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_mechanic_id")
    private User assignedMechanic;

    // Identifies the current round of offers. A mechanic accepting with an old
    // token gets rejected, which stops a stale offer card from winning after
    // the request has already been re-dispatched.
    @Column(length = 64)
    private String currentOfferToken;

    // Who this round went out to. Anyone not in here can't accept it.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "request_offered_to",
            joinColumns = @JoinColumn(name = "request_id"))
    @Column(name = "mechanic_id")
    private Set<Long> offeredTo = new HashSet<>();

    // Grows each time a round times out with nobody accepting.
    private double searchRadiusKm;
    private int searchAttempts = 0;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant acceptedAt;
    private Instant completedAt;

    // Optimistic lock. The in-process lock handles the normal case; this one
    // catches anything that slips past it.
    @Version
    private int version;

    public ServiceRequest(User driver, IssueType issueType, double originLat, double originLng, String note) {
        this.driver = driver;
        this.issueType = issueType;
        this.originLat = originLat;
        this.originLng = originLng;
        this.note = note;
        this.requiredSpecialization = issueType.defaultSpecialization();
    }

    // Start a fresh round of offers and hand back the new token.
    public String startNewOfferRound(Set<Long> mechanicIds) {
        this.currentOfferToken = java.util.UUID.randomUUID().toString();
        this.offeredTo = new HashSet<>(mechanicIds);
        return this.currentOfferToken;
    }

    public boolean wasOfferedTo(Long mechanicId) {
        return offeredTo.contains(mechanicId);
    }

    public boolean isAssigned() {
        return assignedMechanic != null;
    }
}
