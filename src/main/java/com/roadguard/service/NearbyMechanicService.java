package com.roadguard.service;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.web.dto.NearbyMechanicResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Who a driver could call right now: mechanics who are online and close by. It is
 * the same pool the dispatcher draws from, without the skill filter, so the map
 * and the offers agree about who is out there.
 */
@Service
@RequiredArgsConstructor
public class NearbyMechanicService {

    private static final double DEFAULT_RADIUS_KM = 10;
    private static final double MAX_RADIUS_KM = 50;
    private static final int MAX_RESULTS = 40;

    private final MechanicProfileRepository mechanics;

    @Transactional(readOnly = true)
    public List<NearbyMechanicResponse> availableNear(double lat, double lng, Double radiusKm) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new IllegalArgumentException("Those coordinates are off the map");
        }
        double radius = radiusKm == null ? DEFAULT_RADIUS_KM : Math.min(Math.max(radiusKm, 0.5), MAX_RADIUS_KM);

        GeoUtils.BoundingBox box = GeoUtils.boundingBox(lat, lng, radius);
        List<MechanicProfile> pool = mechanics.findInBox(
                AvailabilityStatus.ONLINE, box.minLat(), box.maxLat(), box.minLng(), box.maxLng());

        return pool.stream()
                .filter(MechanicProfile::hasLocation)
                .map(m -> NearbyMechanicResponse.from(m,
                        GeoUtils.haversineKm(lat, lng, m.getCurrentLat(), m.getCurrentLng())))
                .filter(m -> m.distanceKm() <= radius)
                .sorted(Comparator.comparingDouble(NearbyMechanicResponse::distanceKm))
                .limit(MAX_RESULTS)
                .toList();
    }
}
