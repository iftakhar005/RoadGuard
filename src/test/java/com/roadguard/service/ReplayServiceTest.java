package com.roadguard.service;

import com.roadguard.domain.EventLog;
import com.roadguard.domain.LocationUpdate;
import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.IssueType;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.domain.enums.Specialization;
import com.roadguard.repository.EventLogRepository;
import com.roadguard.repository.LocationUpdateRepository;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.repository.UserRepository;
import com.roadguard.security.AuthUser;
import com.roadguard.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReplayServiceTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired EventRecorder eventRecorder;
    @Autowired ReplayService replayService;
    @Autowired AssignmentService assignment;
    @Autowired MechanicService mechanicService;
    @Autowired RealtimeNotifier realtime;
    @Autowired EventLogRepository eventLogs;
    @Autowired LocationUpdateRepository locationUpdates;
    @Autowired ServiceRequestRepository requests;
    @Autowired MechanicProfileRepository mechanics;
    @Autowired UserRepository users;
    @Autowired JwtService jwtService;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mockMvc;

    @TempDir
    Path tempDir;

    private String originalLogDir;
    private String originalReplayDir;

    @BeforeEach
    void setUp() {
        originalLogDir = eventRecorder.getLogDir();
        originalReplayDir = eventRecorder.getReplayDir();
        eventRecorder.setLogDir(tempDir.resolve("logs").toString());
        eventRecorder.setReplayDir(tempDir.resolve("replays").toString());
        eventRecorder.clearLocationThrottle();
    }

    @AfterEach
    void tearDown() {
        eventRecorder.setLogDir(originalLogDir);
        eventRecorder.setReplayDir(originalReplayDir);
        eventRecorder.clearLocationThrottle();
    }

    private record ReplayFixture(User driver, User mechanic, MechanicProfile profile, ServiceRequest request) {
    }

    private ReplayFixture createLiveFixture() {
        return tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User driver = users.save(new User("rep_d_" + n, "rep_d_" + n + "@t.com", "pass", Role.DRIVER));
            User mech = users.save(new User("rep_m_" + n, "rep_m_" + n + "@t.com", "pass", Role.MECHANIC));

            MechanicProfile p = new MechanicProfile(mech);
            p.setSpecializations(Set.of(Specialization.TIRE));
            p.setStatus(AvailabilityStatus.ONLINE);
            p.setCurrentLat(23.8103);
            p.setCurrentLng(90.4125);
            p.setLastHeartbeat(Instant.now());
            mechanics.save(p);

            ServiceRequest req = new ServiceRequest(driver, IssueType.FLAT_TIRE, 23.8103, 90.4125, "replay test");
            req.setAssignedMechanic(mech);
            req.setStatus(RequestStatus.OFFERED);
            requests.save(req);

            return new ReplayFixture(driver, mech, p, req);
        });
    }

    @Test
    @DisplayName("a full job produces ordered events and matching jsonl lines and table rows")
    void fullJobEventLogAndJsonlMatch() throws IOException {
        ReplayFixture fix = createLiveFixture();
        Long reqId = fix.request().getId();
        Long mechId = fix.mechanic().getId();

        // 1. SOS Created
        eventRecorder.record(reqId, "CREATED", "{\"originLat\":23.8103,\"originLng\":90.4125}");

        // 2. Offer sent
        realtime.offersSent(reqId, List.of(mechId));

        // 3. Accepted
        tx.execute(s -> {
            ServiceRequest r = requests.findById(reqId).orElseThrow();
            r.setStatus(RequestStatus.ACCEPTED);
            requests.save(r);
            realtime.requestChanged(r);
            return null;
        });

        // 4. Mechanic location sample
        mechanicService.tellWhoeverIsWaiting(mechId, 23.8110, 90.4130);

        // 5. En route
        assignment.advanceStatus(reqId, mechId, RequestStatus.EN_ROUTE);

        // 6. Arrived
        assignment.advanceStatus(reqId, mechId, RequestStatus.ARRIVED);

        // 7. In progress
        assignment.advanceStatus(reqId, mechId, RequestStatus.IN_PROGRESS);

        // 8. Completed (which triggers snapshot)
        assignment.advanceStatus(reqId, mechId, RequestStatus.COMPLETED);

        // Verify database rows
        List<EventLog> dbLogs = eventLogs.findByRequestIdOrderByTimestampAsc(reqId);
        assertFalse(dbLogs.isEmpty(), "EventLog rows should exist");

        // Verify jsonl file
        Path jsonlFile = tempDir.resolve("logs").resolve("request-" + reqId + ".jsonl");
        assertTrue(Files.isRegularFile(jsonlFile), "jsonl log file should be created");

        List<String> fileLines = Files.readAllLines(jsonlFile);
        assertEquals(dbLogs.size(), fileLines.size(), "jsonl line count must equal event_log table row count");

        // Verify snapshot file created on completion
        Path snapFile = tempDir.resolve("replays").resolve("request-" + reqId + ".ser");
        assertTrue(Files.isRegularFile(snapFile), "snapshot .ser file should be created on completion");

        // Verify ordered timeline
        AuthUser driverAuth = new AuthUser(fix.driver());
        List<ReplayPoint> timeline = replayService.getReplay(driverAuth, reqId);
        assertEquals(dbLogs.size(), timeline.size(), "replay timeline should match total recorded events");

        List<String> types = timeline.stream().map(ReplayPoint::getType).toList();
        assertTrue(types.contains("CREATED"));
        assertTrue(types.contains("OFFER"));
        assertTrue(types.contains("ACCEPTED"));
        assertTrue(types.contains("LOCATION"));
        assertTrue(types.contains("EN_ROUTE"));
        assertTrue(types.contains("ARRIVED"));
        assertTrue(types.contains("IN_PROGRESS"));
        assertTrue(types.contains("COMPLETED"));
    }

    @Test
    @DisplayName("snapshot round-trip serialization and deserialization preserves equality")
    void snapshotRoundTrip() {
        Long reqId = 9999L;
        List<ReplayPoint> points = List.of(
                new ReplayPoint(Instant.now().toString(), "CREATED", 23.81, 90.41, "CREATED"),
                new ReplayPoint(Instant.now().plusSeconds(10).toString(), "OFFER", null, null, null),
                new ReplayPoint(Instant.now().plusSeconds(20).toString(), "ACCEPTED", null, null, "ACCEPTED"),
                new ReplayPoint(Instant.now().plusSeconds(30).toString(), "LOCATION", 23.812, 90.415, null),
                new ReplayPoint(Instant.now().plusSeconds(60).toString(), "COMPLETED", null, null, "COMPLETED")
        );

        ReplaySnapshot original = new ReplaySnapshot(reqId, points);
        eventRecorder.writeSnapshot(original);

        ReplaySnapshot restored = eventRecorder.readSnapshot(reqId).orElseThrow();
        assertEquals(original, restored);
        assertEquals(original.getRequestId(), restored.getRequestId());
        assertEquals(original.getTimeline().size(), restored.getTimeline().size());
        for (int i = 0; i < points.size(); i++) {
            assertEquals(original.getTimeline().get(i), restored.getTimeline().get(i));
        }
    }

    @Test
    @DisplayName("an unwritable logs directory does not break the job or throw exception")
    void unwritableLogDirectoryDoesNotBreakJob() throws IOException {
        // Point logs to a file instead of a directory, causing Files.createDirectories / write to fail
        File blockingFile = tempDir.resolve("blocked-logs").toFile();
        assertTrue(blockingFile.createNewFile());
        eventRecorder.setLogDir(blockingFile.getAbsolutePath());

        ReplayFixture fix = createLiveFixture();
        Long reqId = fix.request().getId();

        // Must not throw any exception
        eventRecorder.record(reqId, "CREATED", "{\"test\":true}");

        // DB record is still saved
        List<EventLog> logs = eventLogs.findByRequestIdOrderByTimestampAsc(reqId);
        assertFalse(logs.isEmpty(), "DB event should be saved even if file I/O fails");
    }

    @Test
    @DisplayName("location samples are throttled to at most one per request per two seconds")
    void locationSampleThrottling() {
        ReplayFixture fix = createLiveFixture();
        Long reqId = fix.request().getId();
        Long mechId = fix.mechanic().getId();

        // Put request on the job
        tx.execute(s -> {
            ServiceRequest r = requests.findById(reqId).orElseThrow();
            r.setStatus(RequestStatus.EN_ROUTE);
            requests.save(r);
            return null;
        });

        // 1st sample: should record
        mechanicService.tellWhoeverIsWaiting(mechId, 23.811, 90.411);

        // 2nd sample immediately after: should be throttled
        mechanicService.tellWhoeverIsWaiting(mechId, 23.812, 90.412);
        mechanicService.tellWhoeverIsWaiting(mechId, 23.813, 90.413);

        List<LocationUpdate> updates = locationUpdates.findByRequestIdOrderByRecordedAtAsc(reqId);
        assertEquals(1, updates.size(), "Immediate subsequent location samples should be throttled");

        // Clear throttle and call again: should record 2nd sample
        eventRecorder.clearLocationThrottle();
        mechanicService.tellWhoeverIsWaiting(mechId, 23.814, 90.414);

        List<LocationUpdate> afterUpdates = locationUpdates.findByRequestIdOrderByRecordedAtAsc(reqId);
        assertEquals(2, afterUpdates.size(), "After throttle period, sample should be recorded");
    }

    @Test
    @DisplayName("replay endpoint requires driver, assigned mechanic, or admin; 403 for strangers")
    void replayEndpointAccessControl() throws Exception {
        ReplayFixture fix = createLiveFixture();
        Long reqId = fix.request().getId();

        // Create some events
        eventRecorder.record(reqId, "CREATED", "{\"originLat\":23.8103,\"originLng\":90.4125}");
        eventRecorder.record(reqId, "ACCEPTED", "{\"status\":\"ACCEPTED\"}");

        String driverToken = jwtService.issue(fix.driver());
        String mechToken = jwtService.issue(fix.mechanic());

        User adminUser = tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            return users.save(new User("rep_admin_" + n, "admin_" + n + "@t.com", "pass", Role.ADMIN));
        });
        String adminToken = jwtService.issue(adminUser);

        User strangerUser = tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            return users.save(new User("rep_stranger_" + n, "stranger_" + n + "@t.com", "pass", Role.DRIVER));
        });
        String strangerToken = jwtService.issue(strangerUser);

        // Driver gets 200
        mockMvc.perform(get("/api/requests/" + reqId + "/replay")
                        .header("Authorization", "Bearer " + driverToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].type").value("CREATED"));

        // Assigned mechanic gets 200
        mockMvc.perform(get("/api/requests/" + reqId + "/replay")
                        .header("Authorization", "Bearer " + mechToken))
                .andExpect(status().isOk());

        // Admin gets 200
        mockMvc.perform(get("/api/requests/" + reqId + "/replay")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Stranger gets 403
        mockMvc.perform(get("/api/requests/" + reqId + "/replay")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
    }
}
