package com.roadguard.web;

import com.roadguard.domain.enums.AcceptOutcome;
import com.roadguard.security.AuthUser;
import com.roadguard.service.AssignmentService;
import com.roadguard.service.RequestService;
import com.roadguard.web.dto.AcceptOfferRequest;
import com.roadguard.web.dto.CreateSosRequest;
import com.roadguard.web.dto.MechanicCandidateResponse;
import com.roadguard.web.dto.ServiceRequestResponse;
import com.roadguard.web.dto.StatusUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/requests")
@RequiredArgsConstructor
public class RequestController {

    private final RequestService requests;
    private final com.roadguard.service.ChatService chat;

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

    @GetMapping("/mine-assigned")
    public ResponseEntity<List<ServiceRequestResponse>> mineAssigned(@AuthenticationPrincipal AuthUser caller) {
        return ResponseEntity.ok(requests.assignedToMe(caller));
    }

    @PostMapping(value = "/{id}/photo", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ServiceRequestResponse> attachPhoto(
            @AuthenticationPrincipal AuthUser caller,
            @PathVariable Long id,
            @org.springframework.web.bind.annotation.RequestPart("file")
            org.springframework.web.multipart.MultipartFile file) {
        return ResponseEntity.ok(requests.attachPhoto(caller, id, file));
    }

    @GetMapping("/{id}/photo")
    public ResponseEntity<Resource> photo(
            @AuthenticationPrincipal AuthUser caller,
            @PathVariable Long id) {
        RequestService.StoredImage image = requests.photoFor(caller, id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(image.resource());
    }

    @GetMapping("/{id}/chat")
    public ResponseEntity<java.util.List<com.roadguard.service.ChatService.Line>> conversation(
            @AuthenticationPrincipal AuthUser caller,
            @PathVariable Long id) {
        return ResponseEntity.ok(chat.conversation(caller, id));
    }

    @GetMapping("/{id}/chat/media/{name}")
    public ResponseEntity<byte[]> chatImage(
            @AuthenticationPrincipal AuthUser caller,
            @PathVariable Long id,
            @PathVariable String name) {

        byte[] bytes = chat.image(caller, id, name);
        String type = name.endsWith(".png") ? "image/png"
                : name.endsWith(".webp") ? "image/webp" : "image/jpeg";

        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, type)
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .body(bytes);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ServiceRequestResponse> byId(
            @AuthenticationPrincipal AuthUser caller,
            @PathVariable Long id) {
        return ResponseEntity.ok(requests.getById(caller, id));
    }

    @GetMapping("/{id}/candidates")
    public ResponseEntity<List<MechanicCandidateResponse>> candidates(
            @AuthenticationPrincipal AuthUser caller,
            @PathVariable Long id) {
        return ResponseEntity.ok(requests.candidatesFor(caller, id));
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<Map<String, Object>> accept(
            @AuthenticationPrincipal AuthUser caller,
            @PathVariable Long id,
            @Valid @RequestBody AcceptOfferRequest req) {

        AcceptOutcome outcome = requests.accept(caller, id, req.offerToken());
        Map<String, Object> body = Map.of(
                "requestId", id,
                "outcome", outcome.name(),
                "won", outcome.isWin());

        return outcome.isWin()
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> updateStatus(
            @AuthenticationPrincipal AuthUser caller,
            @PathVariable Long id,
            @Valid @RequestBody StatusUpdateRequest req) {
        return statusReply(id, requests.advanceStatus(caller, id, req.status()));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(
            @AuthenticationPrincipal AuthUser caller,
            @PathVariable Long id) {
        return statusReply(id, requests.cancel(caller, id));
    }

    private ResponseEntity<Map<String, Object>> statusReply(Long id, AssignmentService.StatusChange change) {
        Map<String, Object> body = Map.of("requestId", id, "result", change.name());
        return switch (change) {
            case OK -> ResponseEntity.ok(body);
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
            case NOT_YOURS -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
            case NOT_ALLOWED -> ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        };
    }

    @PostMapping("/{id}/decline")
    public ResponseEntity<Map<String, Object>> decline(
            @AuthenticationPrincipal AuthUser caller,
            @PathVariable Long id,
            @Valid @RequestBody AcceptOfferRequest req) {

        AcceptOutcome outcome = requests.decline(caller, id, req.offerToken());
        return ResponseEntity.ok(Map.of("requestId", id, "outcome", outcome.name()));
    }
}
