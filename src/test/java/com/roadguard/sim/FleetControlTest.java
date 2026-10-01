package com.roadguard.sim;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.IssueType;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.domain.enums.Specialization;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.repository.UserRepository;
import com.roadguard.service.AssignmentService;
import com.roadguard.service.FleetControl;
import com.roadguard.service.HeartbeatReaper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FleetControlTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired FleetControl fleetControl;
    @Autowired AssignmentService assignment;
    @Autowired MechanicProfileRepository mechanics;
    @Autowired ServiceRequestRepository requests;
    @Autowired UserRepository users;
    @Autowired HeartbeatReaper reaper;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mockMvc;

    @AfterEach
    void tearDown() {
        fleetControl.stop();
        assignment.setSafeMode(true);
    }

    @Test
    @DisplayName("starting then stopping the fleet leaves every simulated mechanic offline")
    void startThenStopLeavesMechanicsOffline() throws Exception {
        fleetControl.start(3);

        boolean allConnected = false;
        for (int i = 0; i < 50; i++) {
            if (fleetControl.status().connected() >= 3) {
                allConnected = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(allConnected, "fleet mechanics should connect and authenticate");

        fleetControl.stop();
        assertFalse(fleetControl.status().running(), "fleet should be marked stopped");

        boolean allOffline = false;
        for (int i = 0; i < 60; i++) {
            Boolean offline = tx.execute(s -> {
                List<MechanicProfile> profiles = mechanics.findAll().stream()
                        .filter(m -> m.getUser().getUsername().startsWith(AccountSource.SIM_PREFIX))
                        .toList();
                return !profiles.isEmpty() && profiles.stream().allMatch(m -> m.getStatus() == AvailabilityStatus.OFFLINE);
            });
            if (Boolean.TRUE.equals(offline)) {
                allOffline = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(allOffline, "stopping the fleet must leave every simulated mechanic OFFLINE");
    }

    @Test
    @DisplayName("race with the lock on produces exactly one winner across repeated runs")
    void raceWithLockOnProducesSingleWinner() throws Exception {
        assignment.setSafeMode(true);

        for (int round = 0; round < 50; round++) {
            List<Long> mechanicIds = createMechanics(6);
            Long requestId = createOfferedRequest(mechanicIds);

            String token = tx.execute(s ->
                    requests.findById(requestId).orElseThrow().getCurrentOfferToken());

            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(mechanicIds.size());
            AtomicInteger won = new AtomicInteger();

            ExecutorService pool = Executors.newFixedThreadPool(mechanicIds.size());
            for (Long mId : mechanicIds) {
                pool.submit(() -> {
                    try {
                        go.await();
                        var outcome = assignment.accept(requestId, mId, token);
                        if (outcome == com.roadguard.domain.enums.AcceptOutcome.ACCEPTED) {
                            won.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }

            go.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            pool.shutdownNow();

            assertEquals(1, won.get(), "with safe mode on, exactly one mechanic must win");
        }
    }

    @Test
    @DisplayName("race with the lock off produces more than one winner at least once")
    void raceWithLockOffProducesMultipleWinners() throws Exception {
        assignment.setSafeMode(false);
        boolean sawCollision = false;

        try {
            for (int round = 0; round < 50; round++) {
                List<Long> mechanicIds = createMechanics(8);
                Long requestId = createOfferedRequest(mechanicIds);

                String token = tx.execute(s ->
                        requests.findById(requestId).orElseThrow().getCurrentOfferToken());

                CountDownLatch go = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(mechanicIds.size());
                AtomicInteger won = new AtomicInteger();

                ExecutorService pool = Executors.newFixedThreadPool(mechanicIds.size());
                for (Long mId : mechanicIds) {
                    pool.submit(() -> {
                        try {
                            go.await();
                            var outcome = assignment.accept(requestId, mId, token);
                            if (outcome == com.roadguard.domain.enums.AcceptOutcome.ACCEPTED) {
                                won.incrementAndGet();
                            }
                        } catch (Exception ignored) {
                        } finally {
                            done.countDown();
                        }
                    });
                }

                go.countDown();
                assertTrue(done.await(5, TimeUnit.SECONDS));
                pool.shutdownNow();

                if (won.get() > 1) {
                    sawCollision = true;
                    break;
                }
            }
            assertTrue(sawCollision, "unsafe mode should produce a collision in 50 tries");
        } finally {
            assignment.setSafeMode(true);
        }
    }

    @Test
    @DisplayName("drop the winner triggers reassignment via heartbeat reaper")
    void dropWinnerTriggersReassignment() throws Exception {
        int n = UNIQUE.incrementAndGet();
        User mech = tx.execute(s -> {
            User u = users.save(new User("drop_m_" + n, "drop_m_" + n + "@test.com", "x", Role.MECHANIC));
            MechanicProfile p = new MechanicProfile(u);
            p.setSpecializations(Set.of(Specialization.GENERAL));
            p.setStatus(AvailabilityStatus.BUSY);
            p.setCurrentLat(23.81);
            p.setCurrentLng(90.41);
            p.setLastHeartbeat(Instant.now().minusSeconds(30));
            mechanics.save(p);
            return u;
        });

        Long requestId = tx.execute(s -> {
            User driver = users.save(new User("drop_d_" + n, "drop_d_" + n + "@test.com", "x", Role.DRIVER));
            ServiceRequest r = new ServiceRequest(driver, IssueType.FLAT_TIRE, 23.81, 90.41, "drop winner test");
            r.setSearchRadiusKm(5);
            r.setAssignedMechanic(mech);
            r.setStatus(RequestStatus.ACCEPTED);
            return requests.save(r).getId();
        });

        reaper.reapOnce();

        ServiceRequest after = tx.execute(s -> requests.findById(requestId).orElseThrow());
        assertTrue(after.getStatus() == RequestStatus.REASSIGNING || after.getStatus() == RequestStatus.SEARCHING,
                "dropped winner should return request to REASSIGNING or SEARCHING");
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    @DisplayName("non-admin is rejected from all fleet endpoints with 403")
    void nonAdminGets403() throws Exception {
        mockMvc.perform(get("/api/admin/fleet")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/fleet/start")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/fleet/stop")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/fleet/race")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"armed\":true}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/fleet/drop-winner")).andExpect(status().isForbidden());
    }

    private List<Long> createMechanics(int count) {
        return tx.execute(s -> {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int n = UNIQUE.incrementAndGet();
                User u = users.save(new User("fc_m_" + n, "fc_m_" + n + "@t.com", "x", Role.MECHANIC));
                MechanicProfile p = new MechanicProfile(u);
                p.setSpecializations(Set.of(Specialization.GENERAL));
                p.setStatus(AvailabilityStatus.ONLINE);
                p.setCurrentLat(23.81);
                p.setCurrentLng(90.41);
                p.setLastHeartbeat(Instant.now());
                mechanics.save(p);
                ids.add(u.getId());
            }
            return ids;
        });
    }

    private Long createOfferedRequest(List<Long> mechanicUserIds) {
        return tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User d = users.save(new User("fc_d_" + n, "fc_d_" + n + "@t.com", "x", Role.DRIVER));
            ServiceRequest r = new ServiceRequest(d, IssueType.FLAT_TIRE, 23.81, 90.41, "race test");
            r.setSearchRadiusKm(10);
            r.startNewOfferRound(new HashSet<>(mechanicUserIds));
            r.setStatus(RequestStatus.OFFERED);
            return requests.save(r).getId();
        });
    }
}
