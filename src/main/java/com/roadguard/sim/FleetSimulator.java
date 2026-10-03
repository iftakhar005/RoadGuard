package com.roadguard.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Puts a fleet of pretend mechanics on the road, each on its own TCP connection to
 * the gateway, so the dispatcher has something to dispatch to and the accept race
 * can be run for real rather than described.
 *
 * <p>Run it with --race and every mechanic offered the same job accepts on the same
 * countdown. With the accept lock on, exactly one wins however many are shouting.
 * With it off, more than one can, and the tally at the end says so.
 */
public class FleetSimulator {

    private final Fleet options;
    private final List<VirtualMechanic> fleet = new ArrayList<>();
    private final Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
    private final Map<Long, Race> races = new ConcurrentHashMap<>();

    private final ExecutorService readers;
    private final ScheduledExecutorService clock = Executors.newScheduledThreadPool(2);

    /** The mechanics holding an offer for one request, and the gun they all wait on. */
    private final class Race {
        private final CountDownLatch gun = new CountDownLatch(1);
        private final List<VirtualMechanic> runners = new ArrayList<>();
        private final AtomicInteger joined = new AtomicInteger();
    }

    private FleetSimulator(Fleet options) {
        this.options = options;
        this.readers = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
    }

    public static void main(String[] args) throws Exception {
        Fleet options = Fleet.fromArgs(args);
        if (options == null) {
            return;
        }
        new FleetSimulator(options).start();
    }

    private void start() throws Exception {
        System.out.printf("Putting %d mechanics on the road, each on its own socket to %s:%d%n",
                options.count(), options.host(), options.port());
        if (options.race()) {
            System.out.println("Race mode: everyone offered a job accepts on the same countdown.");
        }
        System.out.println();

        Registrar registrar = new Registrar(options);
        Random scatter = new Random(20260926L);

        for (int i = 1; i <= options.count(); i++) {
            Registrar.Account account = registrar.signUp(i);

            double lat = options.centreLat() + (scatter.nextDouble() - 0.5) * options.spreadDegrees();
            double lng = options.centreLng() + (scatter.nextDouble() - 0.5) * options.spreadDegrees();

            VirtualMechanic mechanic = new VirtualMechanic(options, this, account, lat, lng, i * 7919L);
            mechanic.connect();
            readers.execute(mechanic);
            fleet.add(mechanic);
        }

        System.out.printf("%n%d connected. Send an SOS near %.4f, %.4f and watch.%n",
                fleet.size(), options.centreLat(), options.centreLng());
        System.out.println("Ctrl-C to bring them home.\n");

        clock.scheduleAtFixedRate(this::tickEveryone,
                options.tickMs(), options.tickMs(), TimeUnit.MILLISECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutDown));
        Thread.currentThread().join();
    }

    private void tickEveryone() {
        for (VirtualMechanic mechanic : fleet) {
            try {
                mechanic.tick();
            } catch (Exception e) {
                options.log(mechanic.name() + " could not report in: " + e.getMessage());
            }
        }
    }

    /**
     * An offer arrived for one of ours.
     *
     * <p>Outside race mode each mechanic dithers for a moment and answers on its own.
     * In race mode the first offer for a request opens a short window, everyone whose
     * offer lands inside it lines up, and then they all answer at once - which is the
     * only way to make several accepts collide inside the same instant rather than
     * politely one after another.
     */
    void offered(VirtualMechanic mechanic, Long requestId, String offerToken) {
        if (!options.race()) {
            clock.schedule(() -> answer(mechanic, requestId, offerToken),
                    mechanic.thinkingTimeMs(), TimeUnit.MILLISECONDS);
            return;
        }

        Race race = races.computeIfAbsent(requestId, id -> {
            Race fresh = new Race();
            clock.schedule(() -> {
                options.log("Request " + id + ": " + fresh.joined.get()
                        + " mechanics are holding an offer, go");
                fresh.gun.countDown();
            }, options.raceGraceMs(), TimeUnit.MILLISECONDS);
            return fresh;
        });

        synchronized (race.runners) {
            race.runners.add(mechanic);
        }
        race.joined.incrementAndGet();

        readers.execute(() -> {
            try {
                race.gun.await();
                answer(mechanic, requestId, offerToken);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void answer(VirtualMechanic mechanic, Long requestId, String offerToken) {
        if (!mechanic.isAlive()) {
            return;
        }
        if (options.declineInstead()) {
            mechanic.decline(requestId, offerToken);
            tally("declined on purpose");
            return;
        }
        mechanic.accept(requestId, offerToken);
    }

    /** Somebody won a job. If we were told to, they now disappear mid-job. */
    void assigned(VirtualMechanic winner) {
        if (!options.killOneMidJob()) {
            return;
        }
        clock.schedule(() -> {
            winner.vanish();
            System.out.println("""

                    The assigned mechanic is gone. Nothing was said on the socket, so the
                    server only finds out when the heartbeats stop. Watch the reaper let the
                    job go and the dispatcher offer it to somebody else.
                    """);
        }, 4000, TimeUnit.MILLISECONDS);
    }

    void tally(String outcome) {
        outcomes.computeIfAbsent(outcome, key -> new AtomicInteger()).incrementAndGet();
    }

    private void shutDown() {
        System.out.println("\nBringing the fleet home.");
        fleet.forEach(VirtualMechanic::stop);
        clock.shutdownNow();
        readers.shutdownNow();

        if (outcomes.isEmpty()) {
            System.out.println("Nothing was offered while they were out.");
            return;
        }

        System.out.println("\nWhat the gateway said back:");
        outcomes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> System.out.printf("  %-28s %d%n", entry.getKey(), entry.getValue().get()));

        int won = outcomes.getOrDefault("ACCEPTED", new AtomicInteger()).get();
        if (won > 1) {
            System.out.printf("%n%d mechanics won the same job. The accept lock was off.%n", won);
        } else if (won == 1) {
            System.out.println("\nOne winner, which is the whole point of the lock.");
        }
    }
}
