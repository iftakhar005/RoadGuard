package com.roadguard.sim;

import com.roadguard.service.GeoUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One pretend mechanic with one real socket.
 *
 * <p>Each of these owns its connection for as long as it lives and reads from it on
 * its own thread, because the gateway does not only answer: it pushes offers down
 * the same stream whenever a job comes up nearby. A thread parked on readLine is
 * what makes that possible, and it is the reason a browser cannot be this client.
 *
 * <p>A mechanic that wins a job does all of it over that one connection: it drives
 * to the driver reporting its position as it goes, then moves the job through
 * arrived, in progress and completed with STATUS commands.
 */
class VirtualMechanic implements Runnable {

    private final Fleet options;
    private final FleetRunner runner;
    private final AccountSource.Account account;
    private final Random dice;

    private Socket socket;
    private PrintWriter out;
    private volatile double lat;
    private volatile double lng;

    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicBoolean authenticated = new AtomicBoolean(false);
    private volatile boolean holdingAJob;
    private volatile boolean driving;
    private volatile Long currentJob;
    private volatile String doing = "waiting for work";

    /* where each offered job is, read off the offer, for the one it ends up winning */
    private final Map<Long, double[]> destinations = new ConcurrentHashMap<>();

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

    String doing() {
        return alive.get() ? doing : "offline";
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
            }
            case "AUTH_FAIL" -> {
                authenticated.set(false);
                options.log(name() + " was refused: " + line);
                stop();
            }
            case "OFFER" -> offer(parts, line);
            case "TAKEN" -> {
                runner.tally("told it was already taken");
                if (!doing.startsWith("lost")) {
                    doing = "lost #" + idOf(parts);
                    runner.tell(name(), "was told request #" + idOf(parts) + " is already taken", "<< " + line);
                }
            }
            case "ACCEPTED" -> {
                runner.toldTheyWon(Long.parseLong(idOf(parts)));
                doing = "accepted #" + idOf(parts);
                runner.tell(name(), "was told ACCEPTED for request #" + idOf(parts), "<< " + line);
            }
            case "ASSIGNED" -> {
                Long id = Long.parseLong(idOf(parts));
                if (id.equals(currentJob)) {
                    return;
                }
                currentJob = id;
                holdingAJob = true;
                doing = "won #" + id;
                runner.tell(name(), "is the assigned mechanic for request #" + id, "<< " + line);
                runner.assigned(this, id);
            }
            case "OK", "STATUS_OK" -> { /* a LOC, HEARTBEAT or STATUS landing; nothing to say */ }
            case "STATUS_REFUSED" -> runner.tell(name(), "was refused a status change", "<< " + line);
            default -> {
                /* the rest are accept outcomes: ALREADY_TAKEN, OFFER_EXPIRED,
                   NOT_OFFERED_TO_YOU, MECHANIC_NOT_AVAILABLE, or ERR ... */
                runner.tally(parts[0]);
                doing = "lost #" + idOf(parts);
                runner.tell(name(), parts[0].equals("ALREADY_TAKEN")
                        ? "lost the race for request #" + idOf(parts) + ", another mechanic got there first"
                        : "was refused: " + parts[0].toLowerCase().replace('_', ' '), "<< " + line);
            }
        }
    }

    private void offer(String[] parts, String line) {
        if (parts.length < 3) {
            return;
        }
        Long id = Long.parseLong(parts[1]);
        if (parts.length >= 8) {
            destinations.put(id, new double[]{Double.parseDouble(parts[6]), Double.parseDouble(parts[7])});
        }

        String away = parts.length >= 6 ? parts[5] + " km away" : "nearby";
        String kind = parts.length >= 5 ? parts[3] + ", " : "";
        doing = "offered #" + id;
        runner.tell(name(), "was offered request #" + id + " (" + kind + away + ")",
                "<< " + line.replace(parts[2], brief(parts[2])));
        runner.offered(this, id, parts[2]);
    }

    /** Drives to the driver, reporting its position each second, then does the job. */
    void beginJob(Long requestId) {
        double[] to = destinations.get(requestId);
        if (to == null) {
            doing = "won #" + requestId + ", but the offer never said where";
            return;
        }

        int seconds = options.driveSeconds();
        double fromLat = lat;
        double fromLng = lng;
        double km = GeoUtils.haversineKm(fromLat, fromLng, to[0], to[1]);

        driving = true;
        doing = "setting off";
        sendStatus(requestId, "EN_ROUTE", "set the job to EN_ROUTE and set off, %.1f km to the driver".formatted(km));

        for (int step = 1; step <= seconds; step++) {
            int n = step;
            runner.later(() -> drive(requestId, fromLat, fromLng, to, km, n, seconds), n * 1000L);
        }
    }

    private void drive(Long requestId, double fromLat, double fromLng, double[] to,
                       double km, int step, int steps) {
        if (!alive.get()) {
            return;
        }
        lat = fromLat + (to[0] - fromLat) * step / steps;
        lng = fromLng + (to[1] - fromLng) * step / steps;
        say("LOC %.5f %.5f".formatted(lat, lng));

        double left = km * (steps - step) / steps;
        doing = step < steps ? "driving, %.1f km to go".formatted(left) : "arrived";

        if (steps >= 4 && step == steps / 2) {
            runner.tell(name(), "is halfway, %.1f km to go".formatted(left), null);
        }
        if (step == steps) {
            driving = false;
            sendStatus(requestId, "ARRIVED", "reached the driver and reported ARRIVED");
            runner.later(() -> work(requestId), options.pauseSeconds() * 1000L);
        }
    }

    private void work(Long requestId) {
        if (!alive.get()) {
            return;
        }
        doing = "working on the fault";
        sendStatus(requestId, "IN_PROGRESS", "started work");
        runner.later(() -> finish(requestId), options.pauseSeconds() * 1000L);
    }

    private void finish(Long requestId) {
        if (!alive.get()) {
            return;
        }
        sendStatus(requestId, "COMPLETED", "finished the job");
        destinations.remove(requestId);
        currentJob = null;
        holdingAJob = false;
        doing = "finished #" + requestId + ", free again";
    }

    private void sendStatus(Long requestId, String target, String sentence) {
        say("STATUS " + requestId + " " + target);
        runner.tell(name(), sentence, ">> STATUS " + requestId + " " + target);
    }

    /** Sends a position and a heartbeat, wandering a little so the map is not frozen. */
    void tick() {
        if (!alive.get()) {
            return;
        }
        if (!driving) {
            if (!holdingAJob) {
                lat += (dice.nextDouble() - 0.5) * options.wanderDegrees();
                lng += (dice.nextDouble() - 0.5) * options.wanderDegrees();
            }
            say("LOC %.5f %.5f".formatted(lat, lng));
        }
        say("HEARTBEAT");
    }

    void accept(Long requestId, String offerToken) {
        say("ACCEPT " + requestId + " " + offerToken);
        doing = "pressed ACCEPT on #" + requestId;
        runner.tell(name(), "pressed ACCEPT on request #" + requestId,
                ">> ACCEPT " + requestId + " " + brief(offerToken));
    }

    void decline(Long requestId, String offerToken) {
        say("DECLINE " + requestId + " " + offerToken);
        doing = "declined #" + requestId;
        runner.tell(name(), "declined request #" + requestId,
                ">> DECLINE " + requestId + " " + brief(offerToken));
    }

    int thinkingTimeMs() {
        int spread = Math.max(1, options.maxThinkMs() - options.minThinkMs());
        return options.minThinkMs() + dice.nextInt(spread);
    }

    /** Walks away without a word, which is what the reaper is there to notice. */
    void vanish() {
        runner.tell(name(), "dropped off the network without saying goodbye", null);
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

    private static String idOf(String[] parts) {
        return parts.length > 1 ? parts[1] : "?";
    }

    /* offer tokens are long and say nothing to a reader; the first few characters do */
    private static String brief(String token) {
        return token.length() > 6 ? token.substring(0, 6) + "..." : token;
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
