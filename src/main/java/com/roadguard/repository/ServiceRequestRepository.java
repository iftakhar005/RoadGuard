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

    // Everything a mechanic is currently on the hook for. The reaper uses this
    // to work out what to re-dispatch when someone drops off.
    List<ServiceRequest> findByAssignedMechanicIdAndStatusIn(Long mechanicId,
                                                             Collection<RequestStatus> statuses);

    // Read a request with the row locked in the database for the rest of the
    // transaction.
    //
    // The accept path already holds an in-process lock, which handles the normal
    // case on its own. This is here for the situation that lock cannot cover -
    // more than one server sharing one database - where two JVMs each hold their
    // own lock and neither knows about the other.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ServiceRequest r WHERE r.id = :id")
    Optional<ServiceRequest> findByIdForUpdate(@Param("id") Long id);

    long countByStatusIn(Collection<RequestStatus> statuses);
}
