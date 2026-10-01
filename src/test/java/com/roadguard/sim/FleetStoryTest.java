package com.roadguard.sim;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.IssueType;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.repository.UserRepository;
import com.roadguard.security.AuthUser;
import com.roadguard.service.AuthService;
import com.roadguard.service.RequestService;
import com.roadguard.tcp.TcpGateway;
import com.roadguard.web.dto.CreateSosRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class FleetStoryTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    /* far from everywhere the other tests put mechanics, so only this fleet is in range */
    private static final double HERE_LAT = 10.0;
    private static final double HERE_LNG = 10.0;

    @Autowired AuthService auth;
    @Autowired RequestService requests;
    @Autowired TcpGateway gateway;
    @Autowired ServiceRequestRepository requestRows;
    @Autowired MechanicProfileRepository mechanics;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;

    private final FleetRunner runner = new FleetRunner();

    @AfterEach
    void bringThemHome() {
        runner.stop();
    }

    private Fleet shortFleet(int count) {
        return new Fleet("", "localhost", gateway.port(), count, HERE_LAT, HERE_LNG,
                0.01, 0.0004, 500, 100, 400, false, 300, false, false, true, 3, 1);
    }

    private ServiceRequest reload(Long id) {
        return tx.execute(s -> {
            ServiceRequest request = requestRows.findById(id).orElseThrow();
            if (request.getAssignedMechanic() != null) {
                request.getAssignedMechanic().getUsername();
            }
            return request;
        });
    }

    private boolean story(FleetRunner.FleetState state, String wirePrefix) {
        return state.story().stream()
                .anyMatch(line -> line.wire() != null && line.wire().startsWith(wirePrefix));
    }

    @Test
    @DisplayName("the winner drives to the driver and finishes the job over its own socket")
    void winnerDoesTheWholeJob() throws Exception {
        runner.start(shortFleet(4), new DirectAccountSource(auth));

        for (int i = 0; i < 100 && runner.connectedCount() < 4; i++) {
            Thread.sleep(100);
        }
        assertEquals(4, runner.connectedCount(), "every mechanic should have signed in");
        Thread.sleep(600);

        AuthUser driver = tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            return new AuthUser(users.save(
                    new User("story_d_" + n, "story_d_" + n + "@test.com", "x", Role.DRIVER)));
        });
        Long requestId = requests.createSos(driver,
                new CreateSosRequest(IssueType.FLAT_TIRE, HERE_LAT, HERE_LNG, "tell the story", false)).id();

        RequestStatus last = null;
        for (int i = 0; i < 300; i++) {
            last = reload(requestId).getStatus();
            if (last == RequestStatus.COMPLETED) {
                break;
            }
            Thread.sleep(100);
        }
        assertEquals(RequestStatus.COMPLETED, last, "the job should have been carried all the way through");

        ServiceRequest done = reload(requestId);
        assertNotNull(done.getAssignedMechanic());
        assertTrue(done.getAssignedMechanic().getUsername().startsWith(AccountSource.SIM_PREFIX),
                "it should have been one of the simulated mechanics");

        MechanicProfile winner = tx.execute(s ->
                mechanics.findByUserId(done.getAssignedMechanic().getId()).orElseThrow());
        assertEquals(HERE_LAT, winner.getCurrentLat(), 0.002, "the winner should have driven to the driver");
        assertEquals(HERE_LNG, winner.getCurrentLng(), 0.002, "the winner should have driven to the driver");

        FleetRunner.FleetState state = runner.snapshot(3);
        for (String wire : List.of("<< OFFER " + requestId, ">> ACCEPT " + requestId,
                "<< ASSIGNED " + requestId,
                ">> STATUS " + requestId + " EN_ROUTE", ">> STATUS " + requestId + " ARRIVED",
                ">> STATUS " + requestId + " IN_PROGRESS", ">> STATUS " + requestId + " COMPLETED")) {
            assertTrue(story(state, wire), "the story should show the protocol line: " + wire);
        }

        long assigned = state.story().stream()
                .filter(line -> line.wire() != null && line.wire().startsWith("<< ASSIGNED " + requestId))
                .count();
        assertEquals(1, assigned, "exactly one mechanic should have been told it won");
    }

    @Test
    @DisplayName("a mechanic who has reached the car stays with it while working")
    void staysWithTheCarWhileWorking() throws Exception {
        runner.start(shortFleet(4).withJobTimes(3, 3), new DirectAccountSource(auth));

        for (int i = 0; i < 100 && runner.connectedCount() < 4; i++) {
            Thread.sleep(100);
        }
        assertEquals(4, runner.connectedCount(), "every mechanic should have signed in");
        Thread.sleep(600);

        AuthUser driver = tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            return new AuthUser(users.save(
                    new User("stay_d_" + n, "stay_d_" + n + "@test.com", "x", Role.DRIVER)));
        });
        Long requestId = requests.createSos(driver,
                new CreateSosRequest(IssueType.FLAT_TIRE, HERE_LAT, HERE_LNG, "stay put", false)).id();

        RequestStatus last = null;
        for (int i = 0; i < 300; i++) {
            last = reload(requestId).getStatus();
            if (last == RequestStatus.IN_PROGRESS) {
                break;
            }
            Thread.sleep(100);
        }
        assertEquals(RequestStatus.IN_PROGRESS, last, "the mechanic should have got to work");

        Long mechanicId = reload(requestId).getAssignedMechanic().getId();
        MechanicProfile before = tx.execute(s -> mechanics.findByUserId(mechanicId).orElseThrow());

        Thread.sleep(1500);

        assertEquals(RequestStatus.IN_PROGRESS, reload(requestId).getStatus(),
                "the job should still be in progress, otherwise this proves nothing");
        MechanicProfile after = tx.execute(s -> mechanics.findByUserId(mechanicId).orElseThrow());

        assertEquals(before.getCurrentLat(), after.getCurrentLat(), 0.0000001,
                "a mechanic working on a car should not drift away from it");
        assertEquals(before.getCurrentLng(), after.getCurrentLng(), 0.0000001,
                "a mechanic working on a car should not drift away from it");
    }

    @Test
    @DisplayName("the story never contains a mechanic's login token")
    void storyHidesCredentials() throws Exception {
        runner.start(shortFleet(2), new DirectAccountSource(auth));
        for (int i = 0; i < 100 && runner.connectedCount() < 2; i++) {
            Thread.sleep(100);
        }

        for (FleetRunner.StoryLine line : runner.snapshot(3).story()) {
            String all = String.valueOf(line.text()) + String.valueOf(line.wire());
            assertTrue(!all.contains("eyJ"), "a token leaked into the story: " + all);
            assertTrue(!all.contains("HELLO"), "the sign-in line should not be shown: " + all);
        }
    }
}
