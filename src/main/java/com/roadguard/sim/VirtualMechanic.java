package com.roadguard.sim;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One pretend mechanic with one real socket.
 *
 * <p>Each of these owns its connection for as long as it lives and reads from it on
 * its own thread, because the gateway does not only answer: it pushes offers down
 * the same stream whenever a job comes up nearby. A thread parked on readLine is
 * what makes that possible, and it is the reason a browser cannot be this client.
 */
class VirtualMechanic implements Runnable {

    private final Fleet options;
    private final FleetRunner runner;
    private final AccountSource.Account account;
    private final Random dice;

    private Socket socket;
    private PrintWriter out;
    private double lat;
    private double lng;

    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicBoolean authenticated = new AtomicBoolean(false);
    private volatile boolean holdingAJob;

    VirtualMechanic(Fleet options, FleetRunner runner, AccountSource.Account account,
                    double lat, double lng, long seed) {
        this.options = options;
        this.runner = runner;
        this.account = account;
        this.lat = lat;
        this.lng = lng;
        this.dice = new Random(seed);
    }

    String name() {
        return account.username();
    }

    Long userId() {
        return account.userId();
    }

    boolean isAlive() {
        return alive.get();
    }

    boolean isConnected() {
        return alive.get() && authenticated.get();
    }

    String state() {
        if (!alive.get()) {
            return "OFFLINE";
        }
        if (holdingAJob) {
            return "BUSY";
        }
        return authenticated.get() ? "ONLINE" : "OFFLINE";
    }

    boolean isHoldingAJob() {
        return holdingAJob;
    }

    void connect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(options.host(), options.port()), 4000);
        socket.setTcpNoDelay(true);

        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        say("HELLO " + account.userId() + " " + account.token());
    }

    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while (alive.get() && (line = in.readLine()) != null) {
                heard(line.trim());
            }
        } catch (IOException e) {
            if (alive.get()) {
                options.log(name() + " lost its connection: " + e.getMessage());
            }
        } finally {
            authenticated.set(false);
            alive.set(false);
        }
    }

    private void heard(String line) {
        if (line.isEmpty()) {
            return;
        }
        String[] parts = line.split("\\s+");

        switch (parts[0]) {
            case "AUTH_OK" -> {
                authenticated.set(true);
                say("LOC %.5f %.5f".formatted(lat, lng));
                options.log(name() + " is on the air");
            }
            case "AUTH_FAIL" -> {
                authenticated.set(false);
                options.log(name() + " was refused: " + line);
                stop();
            }
            case "OFFER" -> {
                if (parts.length >= 3) {
                    runner.offered(this, Long.parseLong(parts[1]), parts[2]);
                }
            }
            case "TAKEN" -> runner.tally("told it was already taken");
            case "ASSIGNED" -> {
                holdingAJob = true;
                options.log(name() + " has been assigned request " + (parts.length > 1 ? parts[1] : "?"));
                runner.assigned(this);
            }
            case "ACCEPTED" -> runner.tally("ACCEPTED");
            case "OK" -> { /* a LOC or HEARTBEAT landing; nothing to do */ }
            default -> {
                /* the rest are accept outcomes: ALREADY_TAKEN, OFFER_EXPIRED,
                   NOT_OFFERED_TO_YOU, MECHANIC_NOT_AVAILABLE, or ERR ... */
                runner.tally(parts[0]);
            }
        }
    }

    /** Sends a position and a heartbeat, wandering a little so the map is not frozen. */
    void tick() {
        if (!alive.get()) {
            return;
        }
        lat += (dice.nextDouble() - 0.5) * options.wanderDegrees();
        lng += (dice.nextDouble() - 0.5) * options.wanderDegrees();

        say("LOC %.5f %.5f".formatted(lat, lng));
        say("HEARTBEAT");
    }

    void accept(Long requestId, String offerToken) {
        say("ACCEPT " + requestId + " " + offerToken);
    }

    void decline(Long requestId, String offerToken) {
        say("DECLINE " + requestId + " " + offerToken);
    }

    int thinkingTimeMs() {
        int spread = Math.max(1, options.maxThinkMs() - options.minThinkMs());
        return options.minThinkMs() + dice.nextInt(spread);
    }

    /** Walks away without a word, which is what the reaper is there to notice. */
    void vanish() {
        options.log(name() + " has dropped off the network without saying goodbye");
        authenticated.set(false);
        alive.set(false);
        closeQuietly();
    }

    void stop() {
        authenticated.set(false);
        if (!alive.getAndSet(false)) {
            return;
        }
        try {
            say("BYE");
        } catch (Exception e) {
            /* going away regardless */
        }
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            /* already gone */
        }
    }

    private synchronized void say(String line) {
        if (out == null) {
            return;
        }
        out.println(line);
        out.flush();
    }
}
