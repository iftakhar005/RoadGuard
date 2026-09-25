package com.roadguard.service;

import com.roadguard.domain.ServiceRequest;
import com.roadguard.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class RealtimeNotifier {

    private final SimpMessagingTemplate messaging;
    private final UserRepository users;
    private final org.springframework.context.ApplicationEventPublisher events;

    public void offersSent(Long requestId, Collection<Long> mechanicUserIds) {
        Map<String, Object> payload = Map.of(
                "type", "OFFER",
                "requestId", requestId,
                "at", Instant.now().toString());

        for (Long userId : mechanicUserIds) {
            sendToUser(userId, payload);
        }
        events.publishEvent(new com.roadguard.tcp.OffersSentEvent(requestId, mechanicUserIds));
        toAdmin("OFFER", requestId);
    }

    public void offerClosed(Long requestId, Collection<Long> mechanicUserIds) {
        Map<String, Object> payload = Map.of(
                "type", "OFFER_CLOSED",
                "requestId", requestId,
                "at", Instant.now().toString());

        for (Long userId : mechanicUserIds) {
            sendToUser(userId, payload);
        }
        events.publishEvent(new com.roadguard.tcp.OfferClosedEvent(requestId, mechanicUserIds));
    }

    public void requestChanged(ServiceRequest request) {
        if (request == null || request.getId() == null) {
            return;
        }
        Map<String, Object> payload = Map.of(
                "type", "STATUS",
                "requestId", request.getId(),
                "status", request.getStatus().name(),
                "at", Instant.now().toString());

        try {
            messaging.convertAndSend("/topic/request/" + request.getId(), payload);
        } catch (Exception e) {
            log.debug("Could not push request update: {}", e.toString());
        }

        if (request.getAssignedMechanic() != null) {
            sendToUser(request.getAssignedMechanic().getId(), payload);
            events.publishEvent(new com.roadguard.tcp.RequestAssignedEvent(
                    request.getId(), request.getAssignedMechanic().getId()));
        }
        toAdmin("STATUS", request.getId());
    }

    public void mechanicMoved(Long requestId, double lat, double lng) {
        Map<String, Object> payload = Map.of(
                "type", "MOVED",
                "requestId", requestId,
                "lat", lat,
                "lng", lng,
                "at", Instant.now().toString());
        try {
            messaging.convertAndSend("/topic/request/" + requestId, payload);
        } catch (Exception e) {
            log.debug("Could not push a movement: {}", e.toString());
        }
    }

    public void mechanicChanged(Long mechanicUserId) {
        sendToUser(mechanicUserId, Map.of(
                "type", "MECHANIC",
                "at", Instant.now().toString()));
        toAdmin("MECHANIC", mechanicUserId);
    }

    private void sendToUser(Long userId, Map<String, Object> payload) {
        if (userId == null) {
            return;
        }
        users.findById(userId).ifPresent(user -> {
            try {
                messaging.convertAndSendToUser(user.getUsername(), "/queue/updates", payload);
            } catch (Exception e) {
                log.debug("Could not push to {}: {}", user.getUsername(), e.toString());
            }
        });
    }

    private void toAdmin(String type, Long id) {
        try {
            messaging.convertAndSend("/topic/admin", Map.of(
                    "type", type,
                    "id", id,
                    "at", Instant.now().toString()));
        } catch (Exception e) {
            log.debug("Could not push to admin: {}", e.toString());
        }
    }
}
