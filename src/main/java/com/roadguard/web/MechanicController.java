package com.roadguard.web;

import com.roadguard.security.AuthUser;
import com.roadguard.service.MechanicService;
import com.roadguard.service.RequestService;
import com.roadguard.web.dto.LocationRequest;
import com.roadguard.web.dto.MechanicProfileResponse;
import com.roadguard.web.dto.MechanicStatusRequest;
import com.roadguard.web.dto.OfferResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/mechanic")
@RequiredArgsConstructor
public class MechanicController {

    private final MechanicService mechanics;
    private final RequestService requests;

    @GetMapping("/me")
    public ResponseEntity<MechanicProfileResponse> me(@AuthenticationPrincipal AuthUser caller) {
        return ResponseEntity.ok(mechanics.myProfile(caller));
    }

    @PostMapping("/status")
    public ResponseEntity<MechanicProfileResponse> setStatus(
            @AuthenticationPrincipal AuthUser caller,
            @Valid @RequestBody MechanicStatusRequest req) {
        return ResponseEntity.ok(mechanics.setStatus(caller, req));
    }

    @PostMapping("/location")
    public ResponseEntity<MechanicProfileResponse> updateLocation(
            @AuthenticationPrincipal AuthUser caller,
            @Valid @RequestBody LocationRequest req) {
        return ResponseEntity.ok(mechanics.updateLocation(caller, req));
    }

    @GetMapping("/offers")
    public ResponseEntity<List<OfferResponse>> openOffers(@AuthenticationPrincipal AuthUser caller) {
        return ResponseEntity.ok(requests.openOffersFor(caller));
    }
}
