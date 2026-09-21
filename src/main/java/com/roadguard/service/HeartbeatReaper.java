package com.roadguard.service;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.repository.ServiceRequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

@Service
@Slf4j
public class HeartbeatReaper {

    private static final List<AvailabilityStatus> CONNECTED =
            List.of(AvailabilityStatus.ONLINE, AvailabilityStatus.BUSY);

    private static final List<RequestStatus> HELD_BY_MECHANIC = List.of(
            RequestStatus.ACCEPTED,
            RequestStatus.EN_ROUTE,
            RequestStatus.ARRIVED,
            RequestStatus.IN_PROGRESS);

    private final MechanicProfileRepository mechanics;
    private final ServiceRequestRepository requests;
    private final AssignmentService assignment;
    private final DispatchService dispatch;
    private final TransactionTemplate tx;

    @Value("${app.heartbeat.timeout-sec:15}")
    private long timeoutSeconds;

    public HeartbeatReaper(MechanicProfileRepository mechanics,
                           ServiceRequestRepository requests,
                           AssignmentService assignment,
                           DispatchService dispatch,
                           TransactionTemplate tx) {
        this.mechanics = mechanics;
        this.requests = requests;
        this.assignment = assignment;
        this.dispatch = dispatch;
        this.tx = tx;
    }

    @Scheduled(
            initialDelayString = "${app.heartbeat.interval-sec:5}000",
            fixedDelayString = "${app.heartbeat.interval-sec:5}000")
    public void scheduledSweep() {
        try {
            ReapResult result = reapOnce();
            if (result.droppedMechanics() > 0) {
                log.info("Reaper dropped {} mechanic(s), re-dispatched {} job(s)",
                        result.droppedMechanics(), result.reassignedRequests());
            }
        } catch (Exception e) {
            log.warn("Reaper sweep failed: {}", e.toString());
        }
    }

    public ReapResult reapOnce() {
        Instant cutoff = Instant.now().minusSeconds(timeoutSeconds);

        List<MechanicProfile> stale =
                mechanics.findByStatusInAndLastHeartbeatBefore(CONNECTED, cutoff);

        int dropped = 0;
        int reassigned = 0;

        for (MechanicProfile profile : stale) {
            Long mechanicUserId = profile.getUser().getId();

            if (markOffline(profile.getId())) {
                dropped++;
            }

            List<ServiceRequest> held =
                    requests.findByAssignedMechanicIdAndStatusIn(mechanicUserId, HELD_BY_MECHANIC);

            for (ServiceRequest request : held) {
                Long requestId = request.getId();
                if (assignment.releaseFromMechanic(requestId, mechanicUserId)) {
                    reassigned++;
                    dispatch.enqueue(requestId, request.getSeverity());
                    log.info("Request {} released from mechanic {} and re-queued",
                            requestId, mechanicUserId);
                }
            }
        }

        return new ReapResult(dropped, reassigned);
    }

    private boolean markOffline(Long profileId) {
        try {
            Boolean changed = tx.execute(status -> {
                MechanicProfile fresh = mechanics.findById(profileId).orElse(null);
                if (fresh == null || fresh.getStatus() == AvailabilityStatus.OFFLINE) {
                    return false;
                }
                fresh.setStatus(AvailabilityStatus.OFFLINE);
                mechanics.save(fresh);
                return true;
            });
            return Boolean.TRUE.equals(changed);
        } catch (OptimisticLockingFailureException e) {
            return false;
        }
    }

    public record ReapResult(int droppedMechanics, int reassignedRequests) {
    }
}
