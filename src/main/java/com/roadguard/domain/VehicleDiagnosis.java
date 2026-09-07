package com.roadguard.domain;

import com.roadguard.domain.enums.Severity;
import com.roadguard.domain.enums.Specialization;
import jakarta.persistence.Column;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "vehicle_diagnoses")
@Getter
@Setter
@NoArgsConstructor
public class VehicleDiagnosis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", unique = true, nullable = false)
    private ServiceRequest request;

    @Column(length = 255)
    private String imagePath;

    @Column(length = 120)
    private String faultCategory;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Specialization specialization;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Severity severity;

    private double confidence;

    @Column(length = 500)
    private String driverGuidance;

    @Column(length = 500)
    private String likelyParts;

    @Column(nullable = false)
    private boolean fallback = false;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public VehicleDiagnosis(ServiceRequest request, Specialization specialization, Severity severity) {
        this.request = request;
        this.specialization = specialization;
        this.severity = severity;
    }
}
