package com.roadguard.repository;

import com.roadguard.domain.VehicleDiagnosis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VehicleDiagnosisRepository extends JpaRepository<VehicleDiagnosis, Long> {

    Optional<VehicleDiagnosis> findByRequestId(Long requestId);

    long countByFallbackTrue();
}
