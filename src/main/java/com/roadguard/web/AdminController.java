package com.roadguard.web;

import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository users;
    private final MechanicProfileRepository mechanics;
    private final ServiceRequestRepository requests;
    private final com.roadguard.service.DispatchService dispatch;
    private final com.roadguard.service.AssignmentService assignment;

    @org.springframework.beans.factory.annotation.Value("${app.demo.allow-unsafe:true}")
    private boolean allowUnsafe;

    @GetMapping("/overview")
    public ResponseEntity<AdminOverviewResponse> overview() {
        List<RequestStatus> activeStatuses = List.of(
                RequestStatus.CREATED,
                RequestStatus.DIAGNOSING,
                RequestStatus.SEARCHING,
                RequestStatus.OFFERED,
                RequestStatus.ACCEPTED,
                RequestStatus.EN_ROUTE,
                RequestStatus.ARRIVED,
                RequestStatus.IN_PROGRESS,
                RequestStatus.REASSIGNING,
                RequestStatus.ESCALATED);
        List<AdminRequestRow> recentRequests = requests.findTop8ByOrderByCreatedAtDesc().stream()
                .map(AdminRequestRow::from)
                .toList();
        List<AdminRequestRow> activeRequestLocations = requests.findByStatusIn(activeStatuses).stream()
                .map(AdminRequestRow::from)
                .toList();
        List<AdminMechanicRow> mechanicLocations = mechanics.findAllByCurrentLatIsNotNullAndCurrentLngIsNotNull()
                .stream()
                .map(AdminMechanicRow::from)
                .toList();

        return ResponseEntity.ok(new AdminOverviewResponse(
                users.countByRole(Role.DRIVER),
                users.countByRole(Role.MECHANIC),
                mechanics.countByStatus(AvailabilityStatus.ONLINE),
                requests.countByStatusIn(activeStatuses),
                recentRequests,
                activeRequestLocations,
                mechanicLocations,
                new EngineState(
                        dispatch.queueDepth(),
                        dispatch.broadcastCount(),
                        assignment.isSafeMode(),
                        allowUnsafe),
                Instant.now()));
    }

    @org.springframework.web.bind.annotation.PostMapping("/safe-mode")
    public ResponseEntity<EngineState> setSafeMode(
            @org.springframework.web.bind.annotation.RequestBody SafeModeRequest body) {

        if (!allowUnsafe && !body.enabled()) {
            throw new IllegalArgumentException(
                    "Turning the accept lock off is only allowed while demo mode is on");
        }
        assignment.setSafeMode(body.enabled());

        return ResponseEntity.ok(new EngineState(
                dispatch.queueDepth(),
                dispatch.broadcastCount(),
                assignment.isSafeMode(),
                allowUnsafe));
    }

    @org.springframework.web.bind.annotation.PostMapping("/requests/{id}/redispatch")
    public ResponseEntity<java.util.Map<String, Object>> redispatch(
            @org.springframework.web.bind.annotation.PathVariable Long id) {

        ServiceRequest request = requests.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No request with id " + id));

        if (request.getStatus().isTerminal()) {
            throw new IllegalArgumentException("That job is already finished");
        }

        boolean revived = assignment.reviveEscalated(id);
        dispatch.enqueue(id, request.getSeverity());

        return ResponseEntity.ok(java.util.Map.of(
                "requestId", id,
                "revived", revived,
                "queued", true));
    }

    public record SafeModeRequest(boolean enabled) {
    }

    public record EngineState(
            int queueDepth,
            long broadcasts,
            boolean safeMode,
            boolean unsafeAllowed) {
    }

    public record AdminOverviewResponse(
            long drivers,
            long mechanics,
            long onlineMechanics,
            long activeRequests,
            List<AdminRequestRow> recentRequests,
            List<AdminRequestRow> activeRequestLocations,
            List<AdminMechanicRow> mechanicLocations,
            EngineState engine,
            Instant generatedAt) {
    }

    public record AdminRequestRow(
            Long id,
            String issueType,
            String status,
            String severity,
            String driverUsername,
            String mechanicUsername,
            double lat,
            double lng,
            Instant createdAt) {

        static AdminRequestRow from(ServiceRequest request) {
            return new AdminRequestRow(
                    request.getId(),
                    request.getIssueType().name(),
                    request.getStatus().name(),
                    request.getSeverity().name(),
                    request.getDriver().getUsername(),
                    request.getAssignedMechanic() == null
                            ? null
                            : request.getAssignedMechanic().getUsername(),
                    request.getOriginLat(),
                    request.getOriginLng(),
                    request.getCreatedAt());
        }
    }

        public record AdminMechanicRow(
                        Long id,
                        String username,
                        String status,
                        String shopName,
                        Double lat,
                        Double lng,
                        double rating) {

                static AdminMechanicRow from(com.roadguard.domain.MechanicProfile mechanic) {
                        return new AdminMechanicRow(
                                        mechanic.getUser().getId(),
                                        mechanic.getUser().getUsername(),
                                        mechanic.getStatus().name(),
                                        mechanic.getShopName(),
                                        mechanic.getCurrentLat(),
                                        mechanic.getCurrentLng(),
                                        mechanic.getAvgRating());
                }
        }
}