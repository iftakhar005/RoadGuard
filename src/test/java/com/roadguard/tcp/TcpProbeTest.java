package com.roadguard.tcp;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.domain.enums.Specialization;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class TcpProbeTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired TcpProbe probe;
    @Autowired UserRepository users;
    @Autowired MechanicProfileRepository mechanics;
    @Autowired TransactionTemplate tx;

    private Long newMechanic(AvailabilityStatus status, Double lat, Double lng) {
        return tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User user = users.save(new User("probe_m_" + n, "probe_m_" + n + "@t.com", "x", Role.MECHANIC));

            MechanicProfile profile = new MechanicProfile(user);
            profile.setSpecializations(new HashSet<>(Set.of(Specialization.TIRE)));
            profile.setStatus(status);
            profile.setCurrentLat(lat);
            profile.setCurrentLng(lng);
            profile.setLastHeartbeat(Instant.now());
            mechanics.save(profile);
            return user.getId();
        });
    }

    private List<String> repliesOf(TcpProbe.Transcript transcript) {
        return transcript.lines().stream()
                .filter(line -> line.direction().equals("received"))
                .map(TcpProbe.Line::text)
                .toList();
    }

    @Test
    @DisplayName("the probe really talks to the gateway and gets the protocol's answers")
    void talksToTheGateway() {
        Long mechanicUserId = newMechanic(AvailabilityStatus.OFFLINE, null, null);

        TcpProbe.Transcript transcript = probe.run(mechanicUserId);

        assertTrue(transcript.reached(), "the probe should have reached the gateway");
        List<String> replies = repliesOf(transcript);

        assertTrue(replies.get(0).startsWith("AUTH_FAIL"), "a rubbish token must be refused first");
        assertEquals("AUTH_OK", replies.get(1), "the real token must be accepted");
        assertEquals("OK", replies.get(2), "a good LOC must be accepted");
        assertTrue(replies.get(3).startsWith("ERR"), "a malformed LOC must be refused");
        assertEquals("OK", replies.get(4), "HEARTBEAT must be accepted");
        assertTrue(replies.get(5).startsWith("ERR unknown command"), "an invented command must be refused");
    }

    @Test
    @DisplayName("the working token never appears in full in the transcript")
    void hidesTheToken() {
        Long mechanicUserId = newMechanic(AvailabilityStatus.OFFLINE, null, null);

        TcpProbe.Transcript transcript = probe.run(mechanicUserId);

        String hello = transcript.lines().stream()
                .map(TcpProbe.Line::text)
                .filter(text -> text.startsWith("HELLO " + mechanicUserId + " ey"))
                .findFirst()
                .orElse(null);

        assertNotNull(hello, "the authenticated HELLO should be in the transcript");
        assertTrue(hello.endsWith("..."), "the token should be cut short: " + hello);
        assertTrue(hello.length() < 40, "the token should not be written out in full: " + hello);
    }

    @Test
    @DisplayName("the mechanic is left exactly as the probe found them")
    void putsTheMechanicBack() {
        /* ONLINE is the case that matters: hanging up is what marks a mechanic offline */
        Long mechanicUserId = newMechanic(AvailabilityStatus.ONLINE, 23.7000, 90.4000);

        probe.run(mechanicUserId);

        MechanicProfile after = tx.execute(s -> mechanics.findByUserId(mechanicUserId).orElseThrow());
        assertEquals(AvailabilityStatus.ONLINE, after.getStatus(), "the probe must not knock them offline");
        assertEquals(23.7000, after.getCurrentLat(), 0.00001, "the probe must not move them");
        assertEquals(90.4000, after.getCurrentLng(), 0.00001, "the probe must not move them");
    }

    @Test
    @DisplayName("an account that is not a mechanic is turned away before any socket is opened")
    void refusesANonMechanic() {
        Long driverUserId = tx.execute(s -> users.save(new User(
                "probe_d_" + UNIQUE.incrementAndGet(),
                "probe_d_" + UNIQUE.get() + "@t.com", "x", Role.DRIVER)).getId());

        TcpProbe.Transcript transcript = probe.run(driverUserId);

        assertTrue(!transcript.reached(), "no connection should have been made");
        assertTrue(transcript.lines().isEmpty(), "there is nothing to transcribe");
        assertTrue(transcript.note().contains("not a mechanic"), transcript.note());
    }
}
