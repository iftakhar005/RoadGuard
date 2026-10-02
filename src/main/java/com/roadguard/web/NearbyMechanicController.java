package com.roadguard.web;

import com.roadguard.service.NearbyMechanicService;
import com.roadguard.web.dto.NearbyMechanicResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/mechanics")
@RequiredArgsConstructor
public class NearbyMechanicController {

    private final NearbyMechanicService nearby;

    @GetMapping("/nearby")
    public ResponseEntity<List<NearbyMechanicResponse>> nearby(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(required = false) Double radiusKm) {
        return ResponseEntity.ok(nearby.availableNear(lat, lng, radiusKm));
    }
}
