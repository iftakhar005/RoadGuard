package com.roadguard.web;

import com.roadguard.security.AuthUser;
import com.roadguard.service.RequestService;
import com.roadguard.web.dto.CreateSosRequest;
import com.roadguard.web.dto.ServiceRequestResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/requests")
@RequiredArgsConstructor
public class RequestController {

    private final RequestService requests;

    @PostMapping
    public ResponseEntity<ServiceRequestResponse> createSos(
            @AuthenticationPrincipal AuthUser caller,
            @Valid @RequestBody CreateSosRequest req) {
        return ResponseEntity.ok(requests.createSos(caller, req));
    }

    @GetMapping("/mine")
    public ResponseEntity<List<ServiceRequestResponse>> mine(@AuthenticationPrincipal AuthUser caller) {
        return ResponseEntity.ok(requests.myRequests(caller));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ServiceRequestResponse> byId(
            @AuthenticationPrincipal AuthUser caller,
            @PathVariable Long id) {
        return ResponseEntity.ok(requests.getById(caller, id));
    }
}
