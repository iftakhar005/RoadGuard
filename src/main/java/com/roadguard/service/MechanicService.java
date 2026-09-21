package com.roadguard.service;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.security.AuthUser;
import com.roadguard.web.dto.LocationRequest;
import com.roadguard.web.dto.MechanicProfileResponse;
import com.roadguard.web.dto.MechanicStatusRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class MechanicService {

    private final MechanicProfileRepository mechanics;

    @Transactional(readOnly = true)
    public MechanicProfileResponse myProfile(AuthUser caller) {
        return MechanicProfileResponse.from(profileOf(caller));
    }

    @Transactional
    public MechanicProfileResponse setStatus(AuthUser caller, MechanicStatusRequest req) {
        MechanicProfile profile = profileOf(caller);

        if (req.lat() != null && req.lng() != null) {
            profile.setCurrentLat(req.lat());
            profile.setCurrentLng(req.lng());
        }

        if (req.status() == AvailabilityStatus.ONLINE && !profile.hasLocation()) {
            throw new IllegalArgumentException(
                    "Send your location before going online, otherwise no job can reach you");
        }

        if (profile.getStatus() == AvailabilityStatus.BUSY
                && req.status() == AvailabilityStatus.ONLINE) {
            throw new IllegalStateException("Finish your current job before going back online");
        }

        profile.setStatus(req.status());
        profile.setLastHeartbeat(Instant.now());
        mechanics.save(profile);
        return MechanicProfileResponse.from(profile);
    }

    @Transactional
    public MechanicProfileResponse updateLocation(AuthUser caller, LocationRequest req) {
        MechanicProfile profile = profileOf(caller);
        profile.setCurrentLat(req.lat());
        profile.setCurrentLng(req.lng());
        profile.setLastHeartbeat(Instant.now());
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
