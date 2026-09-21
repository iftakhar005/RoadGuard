package com.roadguard.service;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.RequestOffer;
import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.AcceptOutcome;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.IssueType;
import com.roadguard.domain.enums.OfferOutcome;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.domain.enums.Specialization;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.repository.RequestOfferRepository;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class AcceptRaceTest {

    private static final int RACERS = 50;
    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired AssignmentService assignment;
    @Autowired ServiceRequestRepository requests;
    @Autowired MechanicProfileRepository mechanics;
    @Autowired RequestOfferRepository offers;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;

    private record Fixture(Long requestId, String token, List<Long> mechanicUserIds) {
    }

    private Fixture seedOfferedRequest(int mechanicCount) {
        return tx.execute(status -> {
            int n = UNIQUE.incrementAndGet();

            User driver = users.save(new User(
                    "race_driver_" + n, "race_driver_" + n + "@test.com", "x", Role.DRIVER));

            List<Long> mechanicIds = new ArrayList<>();
            for (int i = 0; i < mechanicCount; i++) {
                User m = users.save(new User(
                        "race_mech_" + n + "_" + i, "race_mech_" + n + "_" + i + "@test.com", "x", Role.MECHANIC));
                MechanicProfile p = new MechanicProfile(m);
                p.setSpecializations(Set.of(Specialization.TIRE));
                p.setStatus(AvailabilityStatus.ONLINE);
                p.setCurrentLat(23.8103);
                p.setCurrentLng(90.4125);
                mechanics.save(p);
                mechanicIds.add(m.getId());
            }

            ServiceRequest request = new ServiceRequest(
                    driver, IssueType.FLAT_TIRE, 23.8103, 90.4125, "race fixture");
            request.setSearchRadiusKm(5);
            String token = request.startNewOfferRound(new HashSet<>(mechanicIds));
            request.setStatus(RequestStatus.OFFERED);
            requests.save(request);

            List<RequestOffer> rows = new ArrayList<>();
            for (Long id : mechanicIds) {
                rows.add(new RequestOffer(request, users.findById(id).orElseThrow(), token));
            }
            offers.saveAll(rows);

            return new Fixture(request.getId(), token, mechanicIds);
        });
    }

    @RepeatedTest(25)
    @DisplayName("exactly one mechanic wins when all of them accept at the same instant")
    void exactlyOneWinner() throws Exception {
        Fixture f = seedOfferedRequest(RACERS);

        CountDownLatch ready = new CountDownLatch(RACERS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);

        List<Future<AcceptOutcome>> futures = new ArrayList<>();
        for (Long mechanicId : f.mechanicUserIds()) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return assignment.accept(f.requestId(), mechanicId, f.token());
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "threads did not all start");
        go.countDown();

        int accepted = 0;
        int alreadyTaken = 0;
        for (Future<AcceptOutcome> future : futures) {
            AcceptOutcome outcome = future.get(30, TimeUnit.SECONDS);
            if (outcome == AcceptOutcome.ACCEPTED) {
                accepted++;
            } else if (outcome == AcceptOutcome.ALREADY_TAKEN) {
                alreadyTaken++;
            }
        }
        pool.shutdownNow();

        assertEquals(1, accepted, "more than one mechanic won the race");
        assertEquals(RACERS - 1, alreadyTaken, "every loser should be told it was already taken");

        ServiceRequest after = requests.findById(f.requestId()).orElseThrow();
        assertEquals(RequestStatus.ACCEPTED, after.getStatus());
        assertNotNull(after.getAssignedMechanic(), "winner was not recorded");

        long acceptedRows = offers.countByRequestIdAndOutcome(f.requestId(), OfferOutcome.ACCEPTED);
        assertEquals(1, acceptedRows, "exactly one offer row should be marked accepted");
    }

    @Test
    @DisplayName("the winning mechanic is put on the job and marked busy")
    void winnerBecomesBusy() throws Exception {
        Fixture f = seedOfferedRequest(8);

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<AcceptOutcome>> futures = new ArrayList<>();
        for (Long id : f.mechanicUserIds()) {
            futures.add(pool.submit(() -> {
                go.await();
                return assignment.accept(f.requestId(), id, f.token());
            }));
        }
        go.countDown();
        for (Future<AcceptOutcome> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        ServiceRequest after = requests.findById(f.requestId()).orElseThrow();
        Long winnerId = after.getAssignedMechanic().getId();

        MechanicProfile winner = mechanics.findByUserId(winnerId).orElseThrow();
        assertEquals(AvailabilityStatus.BUSY, winner.getStatus(), "winner should be BUSY");

        for (Long id : f.mechanicUserIds()) {
            if (!id.equals(winnerId)) {
                assertEquals(AvailabilityStatus.ONLINE,
                        mechanics.findByUserId(id).orElseThrow().getStatus(),
                        "a losing mechanic must stay ONLINE");
            }
        }
    }

    @Test
    @DisplayName("a stale offer token loses even when the request is still open")
    void staleTokenRejected() {
        Fixture f = seedOfferedRequest(3);

        AcceptOutcome outcome = assignment.accept(
                f.requestId(), f.mechanicUserIds().get(0), "not-the-current-token");

        assertEquals(AcceptOutcome.OFFER_EXPIRED, outcome);
    }

    @Test
    @DisplayName("a mechanic who was never offered the job cannot take it")
    void notOfferedRejected() {
        Fixture f = seedOfferedRequest(2);

        Long outsider = tx.execute(status -> {
            User u = users.save(new User(
                    "outsider_" + UNIQUE.incrementAndGet(),
                    "outsider_" + UNIQUE.get() + "@test.com", "x", Role.MECHANIC));
            MechanicProfile p = new MechanicProfile(u);
            p.setSpecializations(Set.of(Specialization.TIRE));
            p.setStatus(AvailabilityStatus.ONLINE);
            p.setCurrentLat(23.8103);
            p.setCurrentLng(90.4125);
            mechanics.save(p);
            return u.getId();
        });

        AcceptOutcome outcome = assignment.accept(f.requestId(), outsider, f.token());
        assertEquals(AcceptOutcome.NOT_OFFERED_TO_YOU, outcome);
    }

    @Test
    @DisplayName("accepting twice does not reassign the job")
    void secondAcceptIsRejected() {
        Fixture f = seedOfferedRequest(2);
        Long first = f.mechanicUserIds().get(0);
        Long second = f.mechanicUserIds().get(1);

        assertEquals(AcceptOutcome.ACCEPTED, assignment.accept(f.requestId(), first, f.token()));
        assertEquals(AcceptOutcome.ALREADY_TAKEN, assignment.accept(f.requestId(), second, f.token()));

        ServiceRequest after = requests.findById(f.requestId()).orElseThrow();
        assertEquals(first, after.getAssignedMechanic().getId(), "the first accepter must keep the job");
    }
}
