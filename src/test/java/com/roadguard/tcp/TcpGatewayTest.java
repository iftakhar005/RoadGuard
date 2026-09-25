package com.roadguard.tcp;

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
import com.roadguard.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class TcpGatewayTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired TcpGateway gateway;
    @Autowired JwtService tokens;
    @Autowired UserRepository users;
    @Autowired MechanicProfileRepository mechanics;
    @Autowired ServiceRequestRepository requests;
    @Autowired TransactionTemplate tx;

    private record Mechanic(Long userId, String token) {
    }

    private Mechanic newMechanic() {
        return tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User user = users.save(new User("tcp_m_" + n, "tcp_m_" + n + "@t.com", "x", Role.MECHANIC));

            MechanicProfile profile = new MechanicProfile(user);
            profile.setSpecializations(new HashSet<>(Set.of(Specialization.TIRE)));
            profile.setStatus(AvailabilityStatus.OFFLINE);
            profile.setLastHeartbeat(Instant.now());
            mechanics.save(profile);

            return new Mechanic(user.getId(), tokens.issue(user));
        });
    }

    /** A tiny client that speaks the gateway's line protocol. */
    private static final class Device implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader in;
        private final PrintWriter out;

        private Device(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new java.io.OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8), true);
        }

        private String ask(String line) throws IOException {
            out.println(line);
            out.flush();
            return in.readLine();
        }

        private void tell(String line) {
            out.println(line);
            out.flush();
        }

        private String listen() throws IOException {
            return in.readLine();
        }

        /**
         * The gateway talks both ways at once, so an answer can arrive behind a push
         * meant for everyone. Read past anything that is not a reply to this command.
         */
        private String askForOutcome(String line) throws IOException {
            out.println(line);
            out.flush();
            for (int i = 0; i < 10; i++) {
                String reply = in.readLine();
                if (reply == null) {
                    return null;
                }
                if (reply.startsWith("ACCEPTED") || reply.startsWith("ALREADY_TAKEN")
                        || reply.startsWith("OFFER_EXPIRED") || reply.startsWith("MECHANIC")
                        || reply.startsWith("ERR")) {
                    return reply;
                }
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private Device connect() throws IOException {
        assertTrue(gateway.port() > 0, "the gateway should have taken a port");
        return new Device(gateway.port());
    }

    @Test
    @DisplayName("a device with a good token is let in")
    void goodTokenIsAccepted() throws Exception {
        Mechanic me = newMechanic();
        try (Device device = connect()) {
            assertEquals("AUTH_OK", device.ask("HELLO " + me.userId() + " " + me.token()));
        }
    }

    @Test
    @DisplayName("a made up token is refused")
    void badTokenIsRefused() throws Exception {
        Mechanic me = newMechanic();
        try (Device device = connect()) {
            assertTrue(device.ask("HELLO " + me.userId() + " not.a.token").startsWith("AUTH_FAIL"));
        }
    }

    @Test
    @DisplayName("a real token cannot be used to log in as somebody else")
    void tokenCannotBeBorrowed() throws Exception {
        Mechanic me = newMechanic();
        Mechanic other = newMechanic();
        try (Device device = connect()) {
            assertTrue(device.ask("HELLO " + other.userId() + " " + me.token()).startsWith("AUTH_FAIL"));
        }
    }

    @Test
    @DisplayName("nothing works before saying hello")
    void commandsNeedAuthFirst() throws Exception {
        try (Device device = connect()) {
            assertTrue(device.ask("LOC 23.81 90.41").startsWith("ERR"));
            assertTrue(device.ask("HEARTBEAT").startsWith("ERR"));
        }
    }

    @Test
    @DisplayName("a reported position is written down and brings the mechanic on duty")
    void locationIsStored() throws Exception {
        Mechanic me = newMechanic();
        try (Device device = connect()) {
            device.ask("HELLO " + me.userId() + " " + me.token());
            assertEquals("OK", device.ask("LOC 23.7936 90.4043"));
        }

        MechanicProfile after = tx.execute(s -> mechanics.findByUserId(me.userId()).orElseThrow());
        assertEquals(23.7936, after.getCurrentLat(), 0.0001);
        assertEquals(90.4043, after.getCurrentLng(), 0.0001);
    }

    @Test
    @DisplayName("nonsense coordinates are refused rather than stored")
    void rubbishCoordinatesAreRefused() throws Exception {
        Mechanic me = newMechanic();
        try (Device device = connect()) {
            device.ask("HELLO " + me.userId() + " " + me.token());
            assertTrue(device.ask("LOC north a bit").startsWith("ERR"));
            assertTrue(device.ask("LOC 999 999").startsWith("ERR"));
        }
    }

    @Test
    @DisplayName("an unknown command is answered rather than ignored")
    void unknownCommandIsAnswered() throws Exception {
        Mechanic me = newMechanic();
        try (Device device = connect()) {
            device.ask("HELLO " + me.userId() + " " + me.token());
            assertTrue(device.ask("MAKE ME A SANDWICH").startsWith("ERR"));
        }
    }

    @Test
    @DisplayName("an offer sent to a mechanic reaches them down the socket")
    void offerIsPushedToTheDevice() throws Exception {
        Mechanic me = newMechanic();

        try (Device device = connect()) {
            device.ask("HELLO " + me.userId() + " " + me.token());
            device.ask("LOC 23.7936 90.4043");

            Long requestId = tx.execute(s -> {
                int n = UNIQUE.incrementAndGet();
                User driver = users.save(new User("tcp_d_" + n, "tcp_d_" + n + "@t.com", "x", Role.DRIVER));
                ServiceRequest request = new ServiceRequest(
                        driver, IssueType.FLAT_TIRE, 23.7806, 90.4193, "over the wire");
                request.setSearchRadiusKm(25);
                request.startNewOfferRound(Set.of(me.userId()));
                request.setStatus(RequestStatus.OFFERED);
                return requests.save(request).getId();
            });

            gateway.onOffers(new OffersSentEvent(requestId, Set.of(me.userId())));

            String pushed = device.listen();
            assertNotNull(pushed, "the device should have been told about the offer");
            assertTrue(pushed.startsWith("OFFER " + requestId), "got: " + pushed);

            String[] parts = pushed.split("\\s+");
            assertEquals("FLAT_TIRE", parts[3]);
            assertTrue(Double.parseDouble(parts[5]) > 0, "a real distance should be quoted");
        }
    }

    @Test
    @DisplayName("being told the job is taken reaches the device too")
    void takenIsPushedToTheDevice() throws Exception {
        Mechanic me = newMechanic();
        try (Device device = connect()) {
            device.ask("HELLO " + me.userId() + " " + me.token());

            gateway.onTaken(new OfferClosedEvent(4242L, Set.of(me.userId())));

            assertEquals("TAKEN 4242", device.listen());
        }
    }

    @Test
    @DisplayName("winning the race reaches the device")
    void assignedIsPushedToTheDevice() throws Exception {
        Mechanic me = newMechanic();
        try (Device device = connect()) {
            device.ask("HELLO " + me.userId() + " " + me.token());

            gateway.onAssigned(new RequestAssignedEvent(77L, me.userId()));

            assertEquals("ASSIGNED 77", device.listen());
        }
    }

    @Test
    @DisplayName("hanging up takes the mechanic off duty")
    void disconnectingGoesOffDuty() throws Exception {
        Mechanic me = newMechanic();
        Device device = connect();
        device.ask("HELLO " + me.userId() + " " + me.token());
        device.ask("LOC 23.7936 90.4043");

        MechanicProfile whileConnected = tx.execute(s -> mechanics.findByUserId(me.userId()).orElseThrow());
        assertEquals(AvailabilityStatus.ONLINE, whileConnected.getStatus());

        device.tell("BYE");
        device.close();

        for (int i = 0; i < 50; i++) {
            MechanicProfile now = tx.execute(s -> mechanics.findByUserId(me.userId()).orElseThrow());
            if (now.getStatus() == AvailabilityStatus.OFFLINE) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the gateway should have marked the mechanic offline");
    }

    @Test
    @DisplayName("devices racing down sockets still produce exactly one winner")
    void socketsRaceLikeEveryoneElse() throws Exception {
        int racers = 8;
        Mechanic[] crowd = new Mechanic[racers];
        for (int i = 0; i < racers; i++) {
            crowd[i] = newMechanic();
        }

        Long requestId = tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User driver = users.save(new User("tcp_r_" + n, "tcp_r_" + n + "@t.com", "x", Role.DRIVER));
            ServiceRequest request = new ServiceRequest(
                    driver, IssueType.FLAT_TIRE, 23.8103, 90.4125, "race on sockets");
            request.setSearchRadiusKm(25);
            Set<Long> everyone = new HashSet<>();
            for (Mechanic m : crowd) {
                everyone.add(m.userId());
            }
            request.startNewOfferRound(everyone);
            request.setStatus(RequestStatus.OFFERED);
            return requests.save(request).getId();
        });

        String token = tx.execute(s ->
                requests.findById(requestId).orElseThrow().getCurrentOfferToken());

        Device[] devices = new Device[racers];
        for (int i = 0; i < racers; i++) {
            devices[i] = connect();
            devices[i].ask("HELLO " + crowd[i].userId() + " " + crowd[i].token());
            devices[i].ask("LOC 23.8103 90.4125");
        }

        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(racers);
        AtomicInteger winners = new AtomicInteger();
        AtomicReference<String> failure = new AtomicReference<>();

        for (int i = 0; i < racers; i++) {
            Device device = devices[i];
            new Thread(() -> {
                try {
                    go.await();
                    String reply = device.askForOutcome("ACCEPT " + requestId + " " + token);
                    if (reply != null && reply.startsWith("ACCEPTED")) {
                        winners.incrementAndGet();
                    }
                } catch (Exception e) {
                    failure.set(e.toString());
                } finally {
                    done.countDown();
                }
            }).start();
        }

        go.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "every device should have been answered");

        for (Device device : devices) {
            device.close();
        }

        assertEquals(null, failure.get(), "no device should have errored");
        assertEquals(1, winners.get(), "exactly one socket should win the job");
    }
}
