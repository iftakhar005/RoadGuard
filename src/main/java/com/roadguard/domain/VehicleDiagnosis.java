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

// What came back from the AI photo check. Stored rather than just used and
// thrown away, so we can show the driver what it said and check afterwards
// whether it was any good.
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

    // Where the uploaded photo landed on disk.
    @Column(length = 255)
    private String imagePath;

    // Short label the model gave us, e.g. "shredded rear tyre".
    @Column(length = 120)
    private String faultCategory;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Specialization specialization;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Severity severity;

    private double confidence;

    // Safety tip shown to the driver while they wait.
    @Column(length = 500)
    private String driverGuidance;

    // Parts the model thinks might be needed, kept as a JSON array string.
    @Column(length = 500)
    private String likelyParts;

    // True when the AI call failed and we filled this in with safe defaults.
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
