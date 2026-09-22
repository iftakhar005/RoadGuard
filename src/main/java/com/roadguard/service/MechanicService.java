package com.roadguard.service;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.security.AuthUser;
import com.roadguard.web.dto.LocationRequest;
import com.roadguard.web.dto.MechanicProfileResponse;
import com.roadguard.web.dto.MechanicStatusRequest;
import com.roadguard.web.dto.SkillsRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MechanicService {

    private static final List<RequestStatus> ON_THE_JOB = List.of(
            RequestStatus.ACCEPTED,
            RequestStatus.EN_ROUTE,
            RequestStatus.ARRIVED,
            RequestStatus.IN_PROGRESS);

    private final MechanicProfileRepository mechanics;
    private final ServiceRequestRepository requests;
    private final RealtimeNotifier realtime;
    private final OfferTimeoutService timeouts;

    @Transactional(readOnly = true)
    public MechanicProfileResponse myProfile(AuthUser caller) {
        return MechanicProfileResponse.from(profileOf(caller));
    }

    @Transactional
    public MechanicProfileResponse setStatus(AuthUser caller, MechanicStatusRequest req) {
        MechanicProfile profile = profileOf(caller);

        if (req.status() == AvailabilityStatus.BUSY) {
            throw new IllegalArgumentException(
                    "Busy is set by taking a job, not by asking for it");
        }

        if (req.lat() != null && req.lng() != null) {
            profile.setCurrentLat(req.lat());
            profile.setCurrentLng(req.lng());
        }

        if (req.status() == AvailabilityStatus.ONLINE && !profile.hasLocation()) {
            throw new IllegalArgumentException(
                    "Send your location before going online, otherwise no job can reach you");
        }

        if (profile.getStatus() == AvailabilityStatus.BUSY
                && req.status() == AvailabilityStatus.ONLINE
                && !requests.findByAssignedMechanicIdAndStatusIn(caller.getId(), ON_THE_JOB).isEmpty()) {
            throw new IllegalStateException("Finish your current job before going back online");
        }

        boolean cameBack = req.status() == AvailabilityStatus.ONLINE
                && profile.getStatus() != AvailabilityStatus.ONLINE;

        profile.setStatus(req.status());
        profile.setLastHeartbeat(Instant.now());
        mechanics.save(profile);

        if (cameBack) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            timeouts.reviveEscalated();
                        }
                    });
        }

        return MechanicProfileResponse.from(profile);
    }

    @Transactional
    public MechanicProfileResponse updateLocation(AuthUser caller, LocationRequest req) {
        MechanicProfile profile = profileOf(caller);
        profile.setCurrentLat(req.lat());
        profile.setCurrentLng(req.lng());
        profile.setLastHeartbeat(Instant.now());
        mechanics.save(profile);

        tellWhoeverIsWaiting(caller.getId(), req.lat(), req.lng());

        return MechanicProfileResponse.from(profile);
    }

    private void tellWhoeverIsWaiting(Long mechanicUserId, double lat, double lng) {
        requests.findByAssignedMechanicIdAndStatusIn(mechanicUserId, ON_THE_JOB)
                .forEach(job -> realtime.mechanicMoved(job.getId(), lat, lng));
    }

    @Transactional
    public MechanicProfileResponse updateSkills(AuthUser caller, SkillsRequest req) {
        MechanicProfile profile = profileOf(caller);
        profile.replaceSkills(req.specializations());
        mechanics.save(profile);
        return MechanicProfileResponse.from(profile);
    }

    @Transactional
    public MechanicProfileResponse heartbeat(AuthUser caller) {
        MechanicProfile profile = profileOf(caller);
        profile.setLastHeartbeat(Instant.now());
        mechanics.save(profile);
        return MechanicProfileResponse.from(profile);
    }

    private MechanicProfile profileOf(AuthUser caller) {
        return mechanics.findByUserId(caller.getId())
                .orElseThrow(() -> new IllegalStateException("No mechanic profile for this account"));
    }
}
