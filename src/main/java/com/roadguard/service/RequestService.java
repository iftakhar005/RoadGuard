package com.roadguard.service;

import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.domain.enums.Severity;
import com.roadguard.domain.enums.AcceptOutcome;
import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.VehicleDiagnosis;
import com.roadguard.domain.enums.IssueType;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.repository.RequestOfferRepository;
import com.roadguard.repository.VehicleDiagnosisRepository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RequestService {

    private static final List<RequestStatus> ACTIVE_FOR_MECHANIC = List.of(
            RequestStatus.ACCEPTED,
            RequestStatus.EN_ROUTE,
            RequestStatus.ARRIVED,
            RequestStatus.IN_PROGRESS);

    private final ServiceRequestRepository requests;
    private final UserRepository users;
    private final MatchingService matching;
    private final DispatchService dispatch;
    private final AssignmentService assignment;
    private final RequestOfferRepository offerRows;
    private final MechanicProfileRepository mechanics;
    private final VehicleDiagnosisRepository diagnoses;
    private final AiTriageService triage;
    private final TransactionTemplate tx;

    @Value("${app.uploads.dir:uploads}")
    private String uploadsDir;

    @Value("${app.dispatch.urgent-radius-km:10}")
    private double urgentRadiusKm;

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

        if (req.wantsTriage()) {
            if (!request.getStatus().canTransitionTo(RequestStatus.DIAGNOSING)) {
                throw new IllegalStateException(
                        "Cannot start diagnosing from " + request.getStatus());
            }
            request.setStatus(RequestStatus.DIAGNOSING);
            request.beginSearchRound();
            requests.save(request);
            return ServiceRequestResponse.from(request);
        }

        if (!request.getStatus().canTransitionTo(RequestStatus.SEARCHING)) {
            throw new IllegalStateException(
                    "Cannot start searching from " + request.getStatus());
        }
        request.setStatus(RequestStatus.SEARCHING);
        request.beginSearchRound();

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

    public ServiceRequestResponse attachPhoto(AuthUser caller, Long requestId, MultipartFile file) {
        UploadRules.check(file);

        byte[] picture;
        try {
            picture = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("That picture could not be read");
        }

        WaitingFor waiting = tx.execute(status -> {
            ServiceRequest request = requests.findById(requestId)
                    .orElseThrow(() -> new IllegalArgumentException("No request with id " + requestId));

            if (!request.getDriver().getId().equals(caller.getId())) {
                throw new AccessDeniedException("This request is not yours");
            }
            if (request.getStatus() != RequestStatus.DIAGNOSING) {
                throw new IllegalStateException(
                        "This request is no longer waiting for a photo, it is " + request.getStatus());
            }
            return new WaitingFor(request.getIssueType(), request.getNote());
        });

        String stored = storePhoto(requestId, file, picture);

        AiTriageService.Diagnosis result =
                triage.triage(picture, file.getContentType(), waiting.note(), waiting.issueType());

        return tx.execute(status -> {
            ServiceRequest request = requests.findById(requestId)
                    .orElseThrow(() -> new IllegalArgumentException("No request with id " + requestId));

            if (request.getStatus() != RequestStatus.DIAGNOSING) {
                return ServiceRequestResponse.from(request);
            }

            request.setRequiredSpecialization(result.specialization());
            request.setSeverity(result.severity());
            request.setSearchRadiusKm(radiusFor(result.severity()));
            request.setAiFaultCategory(result.faultCategory());
            request.setAiGuidance(result.driverGuidance());

            VehicleDiagnosis diagnosis =
                    new VehicleDiagnosis(request, result.specialization(), result.severity());
            diagnosis.setImagePath(stored);
            diagnosis.setFaultCategory(result.faultCategory());
            diagnosis.setConfidence(result.confidence());
            diagnosis.setDriverGuidance(result.driverGuidance());
            diagnosis.setLikelyParts(result.likelyParts());
            diagnosis.setFallback(result.fallback());
            diagnoses.save(diagnosis);

            if (!request.getStatus().canTransitionTo(RequestStatus.SEARCHING)) {
                throw new IllegalStateException("Cannot start searching from " + request.getStatus());
            }
            request.setStatus(RequestStatus.SEARCHING);
            request.beginSearchRound();
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
        });
    }

    private record WaitingFor(IssueType issueType, String note) {
    }

    private double radiusFor(Severity severity) {
        return severity != null && severity.isUrgent()
                ? urgentRadiusKm
                : defaultRadiusKm;
    }

    private String storePhoto(Long requestId, MultipartFile file, byte[] picture) {
        String name = "sos-" + requestId + UploadRules.extensionFor(file);
        Path directory = Paths.get(uploadsDir, "sos").toAbsolutePath().normalize();
        Path target = UploadRules.within(directory, name);
        try {
            Files.createDirectories(directory);
            Files.write(target, picture);
        } catch (IOException e) {
            throw new IllegalStateException("Could not keep that picture");
        }
        return name;
    }

    @Transactional(readOnly = true)
    public ServiceRequestResponse getById(AuthUser caller, Long id) {
        ServiceRequest request = requests.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No request with id " + id));

        if (!canView(caller, request)) {
            throw new AccessDeniedException("This request is not yours");
        }
        return ServiceRequestResponse.from(request, assignedProfile(request));
    }

    private MechanicProfile assignedProfile(ServiceRequest request) {
        if (request.getAssignedMechanic() == null) {
            return null;
        }
        return mechanics.findByUserId(request.getAssignedMechanic().getId()).orElse(null);
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

    public AssignmentService.StatusChange advanceStatus(AuthUser caller, Long requestId, RequestStatus target) {
        if (caller.getRole() != Role.MECHANIC) {
            throw new AccessDeniedException("Only the assigned mechanic can update a job");
        }
        return assignment.advanceStatus(requestId, caller.getId(), target);
    }

    public AssignmentService.StatusChange cancel(AuthUser caller, Long requestId) {
        if (caller.getRole() != Role.DRIVER) {
            throw new AccessDeniedException("Only the driver can cancel their request");
        }
        return assignment.cancel(requestId, caller.getId());
    }

    @Transactional(readOnly = true)
    public List<OfferResponse> openOffersFor(AuthUser caller) {
        if (caller.getRole() != Role.MECHANIC) {
            throw new AccessDeniedException("Only a mechanic has offers");
        }
        return offerRows.findByMechanicIdAndOutcomeIsNull(caller.getId()).stream()
                .filter(o -> o.getOfferToken().equals(o.getRequest().getCurrentOfferToken()))
                .filter(o -> o.getRequest().getStatus() == RequestStatus.OFFERED)
                .map(offer -> OfferResponse.from(
                        offer,
                        diagnoses.findByRequestId(offer.getRequest().getId())
                                .filter(d -> d.getImagePath() != null && !d.getImagePath().isBlank())
                                .map(d -> "/api/requests/" + offer.getRequest().getId() + "/photo")
                                .orElse(null)))
                .toList();
    }

    @Transactional(readOnly = true)
    public StoredImage photoFor(AuthUser caller, Long requestId) {
        ServiceRequest request = requests.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("No request with id " + requestId));

        boolean activeOffer = caller.getRole() == Role.MECHANIC
                && offerRows.findByMechanicIdAndOutcomeIsNull(caller.getId()).stream()
                        .anyMatch(offer -> offer.getRequest().getId().equals(requestId)
                                && offer.getOfferToken().equals(request.getCurrentOfferToken())
                                && request.getStatus() == RequestStatus.OFFERED);

        if (!canView(caller, request) && !activeOffer) {
            throw new AccessDeniedException("You cannot view this picture");
        }

        VehicleDiagnosis diagnosis = diagnoses.findByRequestId(requestId)
                .orElseThrow(() -> new IllegalArgumentException("This request has no picture"));
        Path file = UploadRules.within(
                Paths.get(uploadsDir, "sos").toAbsolutePath().normalize(), diagnosis.getImagePath());
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("This picture is no longer available");
        }
        return new StoredImage(new FileSystemResource(file), contentTypeFor(diagnosis.getImagePath()));
    }

    private String contentTypeFor(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    public record StoredImage(Resource resource, String contentType) {
    }

    @Transactional(readOnly = true)
    public List<ServiceRequestResponse> assignedToMe(AuthUser caller) {
        if (caller.getRole() != Role.MECHANIC) {
            throw new AccessDeniedException("Only a mechanic has assigned jobs");
        }
        return requests.findByAssignedMechanicIdAndStatusIn(caller.getId(), ACTIVE_FOR_MECHANIC)
                .stream()
                .map(ServiceRequestResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ServiceRequestResponse> myRequests(AuthUser caller) {
        return requests.findByDriverIdOrderByCreatedAtDesc(caller.getId())
                .stream()
                .map(r -> ServiceRequestResponse.from(r, assignedProfile(r)))
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
