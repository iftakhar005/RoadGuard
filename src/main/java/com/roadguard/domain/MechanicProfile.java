package com.roadguard.domain;

import com.roadguard.domain.enums.AvailabilityStatus;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

// Everything about a mechanic that a driver account doesn't need:
// what they can fix, whether they're free, and where they are right now.
@Entity
@Table(name = "mechanic_profiles")
@Getter
@Setter
@NoArgsConstructor
public class MechanicProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true, nullable = false)
    private User user;

    // Fetched eagerly on purpose - the matcher checks these on every single
    // candidate, and there are only a handful per mechanic.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "mechanic_specializations",
            joinColumns = @JoinColumn(name = "mechanic_profile_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "specialization", length = 20)
    private Set<Specialization> specializations = new HashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AvailabilityStatus status = AvailabilityStatus.OFFLINE;

    // Null until they report a position for the first time. The matcher skips
    // anyone without one rather than guessing.
    private Double currentLat;
    private Double currentLng;

    // Last time we heard anything from them. The reaper compares this against
    // the heartbeat timeout to decide if they've dropped off.
    private Instant lastHeartbeat;

    private double avgRating = 0.0;
    private int ratingCount = 0;

    // Optimistic lock. If two threads try to update the same mechanic at once,
    // the second write is rejected instead of silently overwriting the first.
    @Version
    private int version;

    public MechanicProfile(User user) {
        this.user = user;
    }

    public boolean hasSkill(Specialization needed) {
        return specializations.contains(needed);
    }

    public boolean hasLocation() {
        return currentLat != null && currentLng != null;
    }

    // Fold a new star rating into the running average.
    public void addRating(int stars) {
        avgRating = ((avgRating * ratingCount) + stars) / (ratingCount + 1);
        ratingCount++;
    }
}
