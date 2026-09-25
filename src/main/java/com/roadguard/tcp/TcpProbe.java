package com.roadguard.tcp;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.repository.UserRepository;
import com.roadguard.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens a real connection to the device gateway and writes down both sides of the
 * conversation, so the socket protocol can be watched from the admin console
 * instead of from a terminal.
 *
 * <p>A browser cannot open a socket, so this client runs here rather than in the
 * page. Nothing about the gateway is simulated: it is the listening port, the
 * accept loop and the protocol parser doing their ordinary work.
 *
 * <p>Signing in as a mechanic and hanging up would leave that mechanic offline and
 * standing wherever the probe said, so whatever was true of them beforehand is put
 * back at the end.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TcpProbe {

    private final TcpGateway gateway;
    private final JwtService tokens;
    private final UserRepository users;
    private final MechanicProfileRepository mechanics;
    private final TransactionTemplate tx;

    private static final int REPLY_TIMEOUT_MS = 2000;
    private static final int RESTORE_ATTEMPTS = 4;
    private static final int DISCONNECT_WAIT_MS = 1000;

    public record Line(String direction, String text, long atMs) {}

    public record Transcript(boolean reached, String host, int port, String mechanic,
                             List<Line> lines, String note) {}

    public Transcript run(Long mechanicUserId) {
        int port = gateway.port();
        if (port < 0) {
            return new Transcript(false, "127.0.0.1", port, null, List.of(),
                    "The gateway is not listening. Check app.tcp.enabled and whether the port was free at startup.");
        }

        User user = tx.execute(status -> users.findById(mechanicUserId).orElse(null));
        MechanicProfile profile = tx.execute(status -> mechanics.findByUserId(mechanicUserId).orElse(null));
        if (user == null || profile == null) {
            return new Transcript(false, "127.0.0.1", port, null, List.of(),
                    "That account is not a mechanic, so the gateway would refuse it.");
        }

        AvailabilityStatus wasStatus = profile.getStatus();
        Double wasLat = profile.getCurrentLat();
        Double wasLng = profile.getCurrentLng();

        double lat = wasLat != null ? wasLat : 23.8103;
        double lng = wasLng != null ? wasLng : 90.4125;

        List<Line> lines = new ArrayList<>();
        long started = System.currentTimeMillis();
        boolean reached = false;
        String note;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), REPLY_TIMEOUT_MS);
            socket.setSoTimeout(REPLY_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            reached = true;
            lines.add(new Line("note", "connected to 127.0.0.1:" + port, elapsed(started)));

            try (BufferedReader in = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

                exchange(in, out, lines, started, "HELLO " + mechanicUserId + " not-a-real-token");
                exchange(in, out, lines, started, "HELLO " + mechanicUserId + " " + tokens.issue(user));
                exchange(in, out, lines, started, "LOC %.5f %.5f".formatted(lat, lng));
                exchange(in, out, lines, started, "LOC somewhere over there");
                exchange(in, out, lines, started, "HEARTBEAT");
                exchange(in, out, lines, started, "SANDWICH please");

                out.println("BYE");
                out.flush();
                lines.add(new Line("sent", "BYE", elapsed(started)));
                lines.add(new Line("note", "the gateway closed the connection", elapsed(started)));
            }
            note = "The gateway answered every line. The bad token and the two malformed commands were refused by the parser, not by Spring.";
        } catch (IOException e) {
            note = reached
                    ? "The connection dropped part way through: " + e
                    : "Nothing is accepting connections on port " + port + ": " + e;
        }

        restore(mechanicUserId, wasStatus, wasLat, wasLng);
        return new Transcript(reached, "127.0.0.1", port, user.getUsername(), lines, note);
    }

    private void exchange(BufferedReader in, PrintWriter out, List<Line> lines,
                          long started, String command) throws IOException {
        out.println(command);
        out.flush();
        lines.add(new Line("sent", redact(command), elapsed(started)));

        String reply;
        try {
            reply = in.readLine();
        } catch (SocketTimeoutException e) {
            lines.add(new Line("note", "no reply within " + REPLY_TIMEOUT_MS + "ms", elapsed(started)));
            return;
        }
        if (reply == null) {
            lines.add(new Line("note", "the gateway hung up", elapsed(started)));
            throw new IOException("the gateway closed the connection early");
        }
        lines.add(new Line("received", reply, elapsed(started)));
    }

    /* the token is a working credential for that mechanic; it does not belong in a log */
    private String redact(String command) {
        if (!command.startsWith("HELLO ")) {
            return command;
        }
        String[] parts = command.split("\\s+");
        if (parts.length < 3 || parts[2].length() < 12) {
            return command;
        }
        return parts[0] + " " + parts[1] + " " + parts[2].substring(0, 8) + "...";
    }

    /**
     * Hanging up is not instant on the gateway's side. It drops the session and then
     * marks the mechanic offline on its own thread, so writing the old values back
     * straight away races that write and loses it to the version check. Wait for the
     * connection to be let go, then write, and try again if the two still collide.
     */
    private void restore(Long mechanicUserId, AvailabilityStatus status, Double lat, Double lng) {
        awaitDisconnect(mechanicUserId);

        for (int attempt = 1; attempt <= RESTORE_ATTEMPTS; attempt++) {
            try {
                tx.executeWithoutResult(s -> mechanics.findByUserId(mechanicUserId).ifPresent(profile -> {
                    profile.setStatus(status);
                    profile.setCurrentLat(lat);
                    profile.setCurrentLng(lng);
                    mechanics.save(profile);
                }));
                return;
            } catch (OptimisticLockingFailureException e) {
                pause(40);
            } catch (Exception e) {
                log.warn("Could not put mechanic {} back the way the probe found them: {}",
                        mechanicUserId, e.toString());
                return;
            }
        }
        log.warn("Gave up putting mechanic {} back after {} attempts", mechanicUserId, RESTORE_ATTEMPTS);
    }

    private void awaitDisconnect(Long mechanicUserId) {
        long deadline = System.currentTimeMillis() + DISCONNECT_WAIT_MS;
        while (gateway.isConnected(mechanicUserId) && System.currentTimeMillis() < deadline) {
            pause(10);
        }
        /* the gateway drops the session first and marks the mechanic offline after */
        pause(40);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long elapsed(long started) {
        return System.currentTimeMillis() - started;
    }
}
