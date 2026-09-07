package com.roadguard.repository;

import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.enums.RequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ServiceRequestRepository extends JpaRepository<ServiceRequest, Long> {

    List<ServiceRequest> findByDriverIdOrderByCreatedAtDesc(Long driverId);

    List<ServiceRequest> findByStatusIn(Collection<RequestStatus> statuses);

    List<ServiceRequest> findByAssignedMechanicIdAndStatusIn(Long mechanicId,
                                                             Collection<RequestStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ServiceRequest r WHERE r.id = :id")
    Optional<ServiceRequest> findByIdForUpdate(@Param("id") Long id);

    long countByStatusIn(Collection<RequestStatus> statuses);
}
