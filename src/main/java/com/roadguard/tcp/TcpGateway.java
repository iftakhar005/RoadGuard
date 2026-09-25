package com.roadguard.tcp;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.AcceptOutcome;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.repository.UserRepository;
import com.roadguard.security.JwtService;
import com.roadguard.service.AssignmentService;
import com.roadguard.service.GeoUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A plain text gateway for devices that cannot speak HTTP: a tracker bolted into a
 * tow truck, or the fleet simulator. It is only another way in. Every command is
 * handed to the same services the browser uses, so a mechanic on a socket and a
 * mechanic in a browser race for a job on exactly equal terms.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TcpGateway {

    private final JwtService tokens;
    private final UserRepository users;
    private final MechanicProfileRepository mechanics;
    private final ServiceRequestRepository requests;
    private final AssignmentService assignment;
    private final TransactionTemplate tx;

    @Value("${app.tcp.enabled:true}")
    private boolean enabled;

    @Value("${app.tcp.port:9090}")
    private int configuredPort;

    @Value("${app.tcp.workers:8}")
    private int workers;

    private ServerSocket door;
    private ExecutorService handlers;
    private Thread acceptor;
    private volatile boolean running;

    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger connected = new AtomicInteger();

    private static final class Session {
        private final Socket socket;
        private final PrintWriter out;
        private volatile Long mechanicUserId;

        private Session(Socket socket, PrintWriter out) {
            this.socket = socket;
            this.out = out;
        }

        private synchronized void say(String line) {
            out.println(line);
            out.flush();
        }
    }

    @PostConstruct
    public void open() {
        if (!enabled) {
            log.info("Device gateway disabled");
            return;
        }
        try {
            door = new ServerSocket(configuredPort);
        } catch (IOException e) {
            log.warn("Device gateway could not take port {}: {}", configuredPort, e.toString());
            return;
        }

        running = true;
        handlers = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("tcp-mechanic-" + thread.threadId());
            thread.setDaemon(true);
            return thread;
        });

        acceptor = new Thread(this::acceptLoop, "tcp-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();

        log.info("Device gateway listening on {} with {} workers", port(), workers);
    }

    @PreDestroy
    public void close() {
        running = false;
        sessions.values().forEach(session -> quietly(session.socket));
        sessions.clear();
        if (door != null) {
            quietly(door);
        }
        if (handlers != null) {
            handlers.shutdownNow();
            try {
                handlers.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public int port() {
        return door == null ? -1 : door.getLocalPort();
    }

    public int connectedCount() {
        return connected.get();
    }

    public boolean isConnected(Long mechanicUserId) {
        return sessions.containsKey(mechanicUserId);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = door.accept();
                socket.setTcpNoDelay(true);
                handlers.execute(() -> serve(socket));
            } catch (IOException e) {
                if (running) {
                    log.debug("Device gateway stopped accepting: {}", e.toString());
                }
                return;
            }
        }
    }

    private void serve(Socket socket) {
        connected.incrementAndGet();
        Session session = null;

        try (socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(
                     new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            session = new Session(socket, out);

            String line;
            while (running && (line = in.readLine()) != null) {
                String reply = handle(session, line.trim());
                if (reply == null) {
                    break;
                }
                if (!reply.isEmpty()) {
                    session.say(reply);
                }
            }
        } catch (IOException e) {
            log.debug("Device connection ended: {}", e.toString());
        } finally {
            if (session != null && session.mechanicUserId != null) {
                sessions.remove(session.mechanicUserId, session);
                goOffline(session.mechanicUserId);
            }
            connected.decrementAndGet();
        }
    }

    /** Returns the reply to send, an empty string to send nothing, or null to hang up. */
    String handle(Session session, String line) {
        if (line.isEmpty()) {
            return "";
        }

        String[] parts = line.split("\\s+");
        String command = parts[0].toUpperCase();

        if (command.equals("BYE")) {
            return null;
        }
        if (command.equals("HELLO")) {
            return hello(session, parts);
        }
        if (session.mechanicUserId == null) {
            return "ERR say HELLO first";
        }

        return switch (command) {
            case "LOC" -> location(session, parts);
            case "HEARTBEAT" -> beat(session.mechanicUserId);
            case "ACCEPT" -> accept(session.mechanicUserId, parts);
            case "DECLINE" -> decline(session.mechanicUserId, parts);
            default -> "ERR unknown command " + command;
        };
    }

    private String hello(Session session, String[] parts) {
        if (parts.length < 3) {
            return "AUTH_FAIL usage: HELLO <mechanicId> <jwt>";
        }

        Long claimed;
        try {
            claimed = Long.valueOf(parts[1]);
        } catch (NumberFormatException e) {
            return "AUTH_FAIL that is not a mechanic id";
        }

        String username = tokens.usernameOf(parts[2]).orElse(null);
        if (username == null) {
            return "AUTH_FAIL the token is not valid";
        }

        User user = users.findByUsernameIgnoreCase(username).orElse(null);
        if (user == null || !user.getId().equals(claimed)) {
            return "AUTH_FAIL that token belongs to somebody else";
        }
        if (mechanics.findByUserId(user.getId()).isEmpty()) {
            return "AUTH_FAIL only a mechanic can use this gateway";
        }

        session.mechanicUserId = user.getId();
        Session previous = sessions.put(user.getId(), session);
        if (previous != null && previous != session) {
            quietly(previous.socket);
        }
        return "AUTH_OK";
    }

    private String location(Session session, String[] parts) {
        if (parts.length < 3) {
            return "ERR usage: LOC <lat> <lng>";
        }
        double lat;
        double lng;
        try {
            lat = Double.parseDouble(parts[1]);
            lng = Double.parseDouble(parts[2]);
        } catch (NumberFormatException e) {
            return "ERR those are not coordinates";
        }
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            return "ERR those coordinates are off the map";
        }

        Long id = session.mechanicUserId;
        tx.executeWithoutResult(status -> mechanics.findByUserId(id).ifPresent(profile -> {
            profile.setCurrentLat(lat);
            profile.setCurrentLng(lng);
            profile.setLastHeartbeat(Instant.now());
            if (profile.getStatus() == AvailabilityStatus.OFFLINE) {
                profile.setStatus(AvailabilityStatus.ONLINE);
            }
            mechanics.save(profile);
        }));
        return "OK";
    }

    private String beat(Long mechanicUserId) {
        tx.executeWithoutResult(status -> mechanics.findByUserId(mechanicUserId).ifPresent(profile -> {
            profile.setLastHeartbeat(Instant.now());
            mechanics.save(profile);
        }));
        return "OK";
    }

    private String accept(Long mechanicUserId, String[] parts) {
        if (parts.length < 3) {
            return "ERR usage: ACCEPT <requestId> <offerToken>";
        }
        Long requestId;
        try {
            requestId = Long.valueOf(parts[1]);
        } catch (NumberFormatException e) {
            return "ERR that is not a request id";
        }

        AcceptOutcome outcome = assignment.accept(requestId, mechanicUserId, parts[2]);
        if (outcome == AcceptOutcome.ACCEPTED) {
            return "ACCEPTED " + requestId;
        }
        return outcome.name() + " " + requestId;
    }

    private String decline(Long mechanicUserId, String[] parts) {
        if (parts.length < 2) {
            return "ERR usage: DECLINE <requestId> [offerToken]";
        }
        Long requestId;
        try {
            requestId = Long.valueOf(parts[1]);
        } catch (NumberFormatException e) {
            return "ERR that is not a request id";
        }
        assignment.decline(requestId, mechanicUserId, parts.length > 2 ? parts[2] : null);
        return "OK";
    }

    private void goOffline(Long mechanicUserId) {
        try {
            tx.executeWithoutResult(status -> mechanics.findByUserId(mechanicUserId).ifPresent(profile -> {
                if (profile.getStatus() == AvailabilityStatus.ONLINE) {
                    profile.setStatus(AvailabilityStatus.OFFLINE);
                    mechanics.save(profile);
                }
            }));
        } catch (Exception e) {
            log.debug("Could not mark {} offline after a disconnect: {}", mechanicUserId, e.toString());
        }
    }

    @EventListener
    public void onOffers(OffersSentEvent event) {
        push(event.requestId(), event.mechanicUserIds());
    }

    @EventListener
    public void onTaken(OfferClosedEvent event) {
        for (Long mechanicUserId : event.mechanicUserIds()) {
            Session session = sessions.get(mechanicUserId);
            if (session != null) {
                session.say("TAKEN " + event.requestId());
            }
        }
    }

    @EventListener
    public void onAssigned(RequestAssignedEvent event) {
        Session session = sessions.get(event.mechanicUserId());
        if (session != null) {
            session.say("ASSIGNED " + event.requestId());
        }
    }

    private void push(Long requestId, Collection<Long> mechanicUserIds) {
        if (mechanicUserIds.stream().noneMatch(sessions::containsKey)) {
            return;
        }

        ServiceRequest request = tx.execute(status -> requests.findById(requestId).orElse(null));
        if (request == null) {
            return;
        }

        for (Long mechanicUserId : mechanicUserIds) {
            Session session = sessions.get(mechanicUserId);
            if (session == null) {
                continue;
            }
            session.say("OFFER %d %s %s %s %.2f".formatted(
                    request.getId(),
                    request.getCurrentOfferToken(),
                    request.getIssueType(),
                    request.getSeverity(),
                    distanceTo(request, mechanicUserId)));
        }
    }

    private double distanceTo(ServiceRequest request, Long mechanicUserId) {
        MechanicProfile profile = tx.execute(status ->
                mechanics.findByUserId(mechanicUserId).orElse(null));

        if (profile == null || !profile.hasLocation()) {
            return -1;
        }
        return GeoUtils.haversineKm(
                request.getOriginLat(), request.getOriginLng(),
                profile.getCurrentLat(), profile.getCurrentLng());
    }

    private static void quietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            /* it is already going away */
        }
    }
}
