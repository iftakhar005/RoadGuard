package com.roadguard.service;

import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.domain.enums.Severity;
import com.roadguard.domain.enums.AcceptOutcome;
import com.roadguard.repository.RequestOfferRepository;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.repository.UserRepository;
import com.roadguard.security.AuthUser;
import com.roadguard.web.dto.CreateSosRequest;
import com.roadguard.web.dto.MechanicCandidateResponse;
import com.roadguard.web.dto.OfferResponse;
import com.roadguard.web.dto.ServiceRequestResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RequestService {

    private final ServiceRequestRepository requests;
    private final UserRepository users;
    private final MatchingService matching;
    private final DispatchService dispatch;
    private final AssignmentService assignment;
    private final RequestOfferRepository offerRows;

    @Value("${app.dispatch.default-radius-km:5}")
    private double defaultRadiusKm;

    @Transactional
    public ServiceRequestResponse createSos(AuthUser caller, CreateSosRequest req) {
        if (caller.getRole() != Role.DRIVER) {
            throw new AccessDeniedException("Only a driver can send an SOS");
        }

        User driver = users.findById(caller.getId())
                .orElseThrow(() -> new IllegalStateException("Account no longer exists"));

        ServiceRequest request = new ServiceRequest(
                driver,
                req.issueType(),
                req.originLat(),
                req.originLng(),
                req.note());

        request.setSearchRadiusKm(defaultRadiusKm);

        if (!request.getStatus().canTransitionTo(RequestStatus.SEARCHING)) {
            throw new IllegalStateException(
                    "Cannot start searching from " + request.getStatus());
        }
        request.setStatus(RequestStatus.SEARCHING);

        requests.save(request);

        Long id = request.getId();
        Severity severity = request.getSeverity();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch.enqueue(id, severity);
            }
        });

        return ServiceRequestResponse.from(request);
    }

    @Transactional(readOnly = true)
    public ServiceRequestResponse getById(AuthUser caller, Long id) {
        ServiceRequest request = requests.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No request with id " + id));

        if (!canView(caller, request)) {
            throw new AccessDeniedException("This request is not yours");
        }
        return ServiceRequestResponse.from(request);
    }

    @Transactional(readOnly = true)
    public List<MechanicCandidateResponse> candidatesFor(AuthUser caller, Long requestId) {
        ServiceRequest request = requests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("No request with id " + requestId));

        if (caller.getRole() != Role.ADMIN
                && !request.getDriver().getId().equals(caller.getId())) {
            throw new AccessDeniedException("This request is not yours");
        }

        return matching.findCandidates(request).stream()
                .map(MechanicCandidateResponse::from)
                .toList();
    }

    public AcceptOutcome accept(AuthUser caller, Long requestId, String offerToken) {
        if (caller.getRole() != Role.MECHANIC) {
            throw new AccessDeniedException("Only a mechanic can accept a job");
        }
        return assignment.accept(requestId, caller.getId(), offerToken);
    }

    public AcceptOutcome decline(AuthUser caller, Long requestId, String offerToken) {
        if (caller.getRole() != Role.MECHANIC) {
            throw new AccessDeniedException("Only a mechanic can decline a job");
        }
        return assignment.decline(requestId, caller.getId(), offerToken);
    }

    @Transactional(readOnly = true)
    public List<OfferResponse> openOffersFor(AuthUser caller) {
        if (caller.getRole() != Role.MECHANIC) {
            throw new AccessDeniedException("Only a mechanic has offers");
        }
        return offerRows.findByMechanicIdAndOutcomeIsNull(caller.getId()).stream()
                .filter(o -> o.getOfferToken().equals(o.getRequest().getCurrentOfferToken()))
                .filter(o -> o.getRequest().getStatus() == RequestStatus.OFFERED)
                .map(OfferResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ServiceRequestResponse> myRequests(AuthUser caller) {
        return requests.findByDriverIdOrderByCreatedAtDesc(caller.getId())
                .stream()
                .map(ServiceRequestResponse::from)
                .toList();
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
