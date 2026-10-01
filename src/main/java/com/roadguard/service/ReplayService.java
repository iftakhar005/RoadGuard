package com.roadguard.service;

import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.enums.Role;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReplayService {

    private final EventRecorder recorder;
    private final ServiceRequestRepository requests;

    @Transactional(readOnly = true)
    public List<ReplayPoint> getReplay(AuthUser caller, Long requestId) {
        ServiceRequest request = requests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("No request with id " + requestId));

        if (!canView(caller, request)) {
            throw new AccessDeniedException("You are not allowed to view the replay for this request");
        }

        Optional<ReplaySnapshot> snapshot = recorder.readSnapshot(requestId);
        if (snapshot.isPresent()) {
            return snapshot.get().getTimeline();
        }

        return recorder.buildTimelineFromLogs(requestId);
    }

    private boolean canView(AuthUser caller, ServiceRequest request) {
        if (caller.getRole() == Role.ADMIN) {
            return true;
        }
        if (request.getDriver().getId().equals(caller.getId())) {
            return true;
        }
        return request.getAssignedMechanic() != null
                && request.getAssignedMechanic().getId().equals(caller.getId());
    }
}
