package com.roadguard.service;

import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.repository.ServiceRequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@Slf4j
public class OfferTimeoutService {

    private final ServiceRequestRepository requests;
    private final AssignmentService assignment;
    private final DispatchService dispatch;

    @Value("${app.dispatch.offer-timeout-sec:20}")
    private long offerTimeoutSeconds;

    @Value("${app.dispatch.max-radius-km:25}")
    private double maxRadiusKm;

    @Value("${app.dispatch.max-attempts:3}")
    private int maxAttempts;

    public OfferTimeoutService(ServiceRequestRepository requests,
                               AssignmentService assignment,
                               DispatchService dispatch) {
        this.requests = requests;
        this.assignment = assignment;
        this.dispatch = dispatch;
    }

    @Scheduled(
            initialDelayString = "${app.dispatch.offer-timeout-sec:20}000",
            fixedDelayString = "${app.dispatch.offer-timeout-sec:20}000")
    public void scheduledSweep() {
        try {
            SweepResult result = sweepOnce();
            if (result.widened() > 0 || result.escalated() > 0) {
                log.info("Offer timeout: widened {} request(s), escalated {}",
                        result.widened(), result.escalated());
            }
        } catch (Exception e) {
            log.warn("Offer timeout sweep failed: {}", e.toString());
        }
    }

    public SweepResult sweepOnce() {
        Instant cutoff = Instant.now().minusSeconds(offerTimeoutSeconds);

        List<ServiceRequest> timedOut =
                requests.findByStatusAndOfferedAtBefore(RequestStatus.OFFERED, cutoff);

        int widened = 0;
        int escalated = 0;

        for (ServiceRequest request : timedOut) {
            Long requestId = request.getId();
            AssignmentService.ExpiryOutcome outcome = assignment.expireRound(
                    requestId, request.getCurrentOfferToken(), maxRadiusKm, maxAttempts);

            switch (outcome) {
                case WIDENED -> {
                    widened++;
                    dispatch.enqueue(requestId, request.getSeverity());
                    log.info("Request {} had no takers, widening search and trying again", requestId);
                }
                case ESCALATED -> {
                    escalated++;
                    log.info("Request {} escalated to admin after {} attempts",
                            requestId, maxAttempts);
                }
                case NOT_EXPIRED -> {
                }
            }
        }

        return new SweepResult(widened, escalated);
    }

    public record SweepResult(int widened, int escalated) {
    }
}
