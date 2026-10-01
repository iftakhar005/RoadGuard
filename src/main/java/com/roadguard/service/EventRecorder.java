package com.roadguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadguard.domain.EventLog;
import com.roadguard.domain.LocationUpdate;
import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.repository.EventLogRepository;
import com.roadguard.repository.LocationUpdateRepository;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventRecorder {

    private final EventLogRepository eventLogs;
    private final LocationUpdateRepository locationUpdates;
    private final ServiceRequestRepository requests;
    private final UserRepository users;
    private final ObjectMapper objectMapper;

    @Value("${app.events.log-dir:logs}")
    private String logDir;

    @Value("${app.events.replay-dir:replays}")
    private String replayDir;

    private final ConcurrentHashMap<Long, Instant> lastLocationTime = new ConcurrentHashMap<>();

    public void setLogDir(String logDir) {
        this.logDir = logDir;
    }

    public void setReplayDir(String replayDir) {
        this.replayDir = replayDir;
    }

    public String getLogDir() {
        return logDir;
    }

    public String getReplayDir() {
        return replayDir;
    }

    public void clearLocationThrottle() {
        lastLocationTime.clear();
    }

    public void record(Long requestId, String type, String payloadJson) {
        if (requestId == null || type == null) {
            return;
        }

        Instant now = Instant.now();

        try {
            EventLog event = new EventLog(requestId, type, payloadJson);
            event.setTimestamp(now);
            eventLogs.save(event);
        } catch (Exception e) {
            log.warn("Could not save event log row for request {}: {}", requestId, e.getMessage());
        }

        try {
            Path file = Paths.get(logDir, "request-" + requestId + ".jsonl").toAbsolutePath().normalize();
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("t", now.toString());
            map.put("requestId", requestId);
            map.put("type", type);
            map.put("payload", payloadJson);
            String jsonLine = objectMapper.writeValueAsString(map) + System.lineSeparator();
            Files.writeString(file, jsonLine, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("Could not write event log file for request {}: {}", requestId, e.getMessage());
        }
    }

    public void recordLocation(Long requestId, Long mechanicUserId, double lat, double lng) {
        if (requestId == null) {
            return;
        }

        Instant now = Instant.now();
        Instant last = lastLocationTime.get(requestId);
        if (last != null && Duration.between(last, now).toMillis() < 2000) {
            return;
        }
        lastLocationTime.put(requestId, now);

        try {
            ServiceRequest reqRef = requests.findById(requestId).orElse(null);
            User mechRef = mechanicUserId != null ? users.findById(mechanicUserId).orElse(null) : null;
            if (reqRef != null && mechRef != null) {
                LocationUpdate update = new LocationUpdate(mechRef, reqRef, lat, lng);
                update.setRecordedAt(now);
                locationUpdates.save(update);
            }
        } catch (Exception e) {
            log.warn("Could not save location update entity for request {}: {}", requestId, e.getMessage());
        }

        record(requestId, "LOCATION", "{\"lat\":" + lat + ",\"lng\":" + lng + "}");
    }

    public void createSnapshot(Long requestId) {
        if (requestId == null) {
            return;
        }

        try {
            List<ReplayPoint> timeline = buildTimelineFromLogs(requestId);
            ReplaySnapshot snapshot = new ReplaySnapshot(requestId, timeline);
            writeSnapshot(snapshot);
        } catch (Exception e) {
            log.warn("Could not create replay snapshot for request {}: {}", requestId, e.getMessage());
        }
    }

    public void writeSnapshot(ReplaySnapshot snapshot) {
        try {
            Path file = Paths.get(replayDir, "request-" + snapshot.getRequestId() + ".ser").toAbsolutePath().normalize();
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(file))) {
                oos.writeObject(snapshot);
            }
        } catch (Exception e) {
            log.warn("Could not write snapshot file for request {}: {}", snapshot.getRequestId(), e.getMessage());
        }
    }

    public Optional<ReplaySnapshot> readSnapshot(Long requestId) {
        Path file = Paths.get(replayDir, "request-" + requestId + ".ser").toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(file))) {
            Object obj = ois.readObject();
            if (obj instanceof ReplaySnapshot snapshot) {
                return Optional.of(snapshot);
            }
        } catch (Exception e) {
            log.warn("Could not read snapshot file for request {}: {}", requestId, e.getMessage());
        }
        return Optional.empty();
    }

    public List<ReplayPoint> buildTimelineFromLogs(Long requestId) {
        List<EventLog> events = eventLogs.findByRequestIdOrderByTimestampAsc(requestId);
        List<ReplayPoint> points = new ArrayList<>();
        for (EventLog e : events) {
            points.add(toReplayPoint(e));
        }
        return points;
    }

    private ReplayPoint toReplayPoint(EventLog event) {
        String t = event.getTimestamp().toString();
        String type = event.getType();
        Double lat = null;
        Double lng = null;
        String status = null;

        if (event.getPayload() != null && !event.getPayload().isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(event.getPayload());
                if (node.has("lat") && !node.get("lat").isNull()) {
                    lat = node.get("lat").asDouble();
                } else if (node.has("originLat") && !node.get("originLat").isNull()) {
                    lat = node.get("originLat").asDouble();
                }
                if (node.has("lng") && !node.get("lng").isNull()) {
                    lng = node.get("lng").asDouble();
                } else if (node.has("originLng") && !node.get("originLng").isNull()) {
                    lng = node.get("originLng").asDouble();
                }
                if (node.has("status") && !node.get("status").isNull()) {
                    status = node.get("status").asText();
                }
            } catch (Exception ignored) {
            }
        }

        if (status == null && isStatusName(type)) {
            status = type;
        }

        return new ReplayPoint(t, type, lat, lng, status);
    }

    private boolean isStatusName(String s) {
        try {
            RequestStatus.valueOf(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
