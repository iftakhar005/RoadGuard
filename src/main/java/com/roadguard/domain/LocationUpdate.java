package com.roadguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

// One point on a mechanic's trail. These are what the driver's map animates,
// and what the replay screen plays back after a job is finished.
//
// Not every ping gets stored - that would fill the table fast. Live positions
// are kept in memory and only sampled to here.
@Entity
@Table(name = "location_updates")
@Getter
@Setter
@NoArgsConstructor
public class LocationUpdate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Null for pings sent while the mechanic isn't on a job.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id")
    private ServiceRequest request;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "mechanic_id", nullable = false)
    private User mechanic;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;

    @Column(nullable = false)
    private Instant recordedAt = Instant.now();

    public LocationUpdate(User mechanic, ServiceRequest request, double lat, double lng) {
        this.mechanic = mechanic;
        this.request = request;
        this.lat = lat;
        this.lng = lng;
    }
}
