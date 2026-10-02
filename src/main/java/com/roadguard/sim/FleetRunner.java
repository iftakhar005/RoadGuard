package com.roadguard.sim;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class FleetRunner {

    public record MechanicState(String name, String state, String doing) {
    }

    /** One line of what the fleet is up to, with the raw protocol line beside it when there is one. */
    public record StoryLine(String at, String who, String text, String wire) {
    }

    public record FleetState(
            boolean running,
            int size,
            int connected,
            boolean raceArmed,
            int offerCount,
            Map<String, Integer> outcomes,
            List<MechanicState> mechanics,
            List<StoryLine> story,
            Long lastJobId,
            int lastJobWinners) {
    }

    private static final int STORY_LENGTH = 40;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private volatile Fleet options;
    private final List<VirtualMechanic> fleet = new ArrayList<>();
    private final Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
    private final Map<Long, Race> races = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<StoryLine> story = new ConcurrentLinkedDeque<>();
    private final Map<Long, AtomicInteger> toldTheyWon = new ConcurrentHashMap<>();
    private volatile Long lastJobId;

    private ExecutorService readers;
    private ScheduledExecutorService clock;

    private volatile boolean running;
    private volatile boolean raceArmed;
    private volatile boolean dropNextWinner;

    private final class Race {
        private final CountDownLatch gun = new CountDownLatch(1);
        private final List<VirtualMechanic> runners = new ArrayList<>();
        private final AtomicInteger joined = new AtomicInteger();
    }

    public synchronized boolean start(Fleet options, AccountSource accountSource) throws Exception {
        if (running) {
            return false;
        }
        this.options = options;
        this.raceArmed = options.race();
        this.dropNextWinner = options.killOneMidJob();
        this.fleet.clear();
        this.outcomes.clear();
        this.races.clear();
        this.story.clear();
        this.toldTheyWon.clear();
        this.lastJobId = null;

        this.readers = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        this.clock = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });

        Random scatter = new Random(20260926L);
        for (int i = 1; i <= options.count(); i++) {
            AccountSource.Account account = accountSource.getAccount(i);

            double lat = options.centreLat() + (scatter.nextDouble() - 0.5) * options.spreadDegrees();
            double lng = options.centreLng() + (scatter.nextDouble() - 0.5) * options.spreadDegrees();

            VirtualMechanic mechanic = new VirtualMechanic(options, this, account, lat, lng, i * 7919L);
            mechanic.connect();
            readers.execute(mechanic);
            fleet.add(mechanic);
        }

        clock.scheduleAtFixedRate(this::tickEveryone,
                options.tickMs(), options.tickMs(), TimeUnit.MILLISECONDS);
        running = true;
        tell("fleet", "%d mechanics are on the road, each with its own TCP connection to port %d"
                .formatted(fleet.size(), options.port()), null);
        return true;
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        tell("fleet", "all mechanics hung up (BYE) and went offline", null);
        for (VirtualMechanic mechanic : fleet) {
            mechanic.stop();
        }
        if (clock != null) {
            clock.shutdownNow();
        }
        if (readers != null) {
            readers.shutdownNow();
        }
    }

    public void setRace(boolean armed) {
        this.raceArmed = armed;
    }

    public boolean isRaceArmed() {
        return raceArmed;
    }

    public void dropWinner() {
        this.dropNextWinner = true;
        synchronized (fleet) {
            for (VirtualMechanic mechanic : fleet) {
                if (mechanic.isHoldingAJob()) {
                    mechanic.vanish();
                }
            }
        }
    }

    private void tickEveryone() {
        for (VirtualMechanic mechanic : fleet) {
            try {
                mechanic.tick();
            } catch (Exception e) {
                if (options != null) {
                    options.log(mechanic.name() + " could not report in: " + e.getMessage());
                }
            }
        }
    }

    void offered(VirtualMechanic mechanic, Long requestId, String offerToken) {
        if (!raceArmed) {
            clock.schedule(() -> answer(mechanic, requestId, offerToken),
                    mechanic.thinkingTimeMs(), TimeUnit.MILLISECONDS);
            return;
        }

        Race race = races.computeIfAbsent(requestId, id -> {
            Race fresh = new Race();
            clock.schedule(() -> {
                tell("fleet", "request #%d: %d mechanics are holding an offer and all answer at the same instant"
                        .formatted(id, fresh.joined.get()), null);
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
        if (options != null && options.declineInstead()) {
            mechanic.decline(requestId, offerToken);
            tally("declined on purpose");
            return;
        }
        mechanic.accept(requestId, offerToken);
    }

    void assigned(VirtualMechanic winner, Long requestId) {
        if (dropNextWinner || (options != null && options.killOneMidJob())) {
            clock.schedule(winner::vanish, 1000, TimeUnit.MILLISECONDS);
            return;
        }
        if (options != null && options.doesTheJob()) {
            winner.beginJob(requestId);
        }
    }

    void later(Runnable step, long millis) {
        ScheduledExecutorService timer = clock;
        if (timer != null && !timer.isShutdown()) {
            timer.schedule(step, millis, TimeUnit.MILLISECONDS);
        }
    }

    /** Writes one line to the story the admin page shows, and to the console for the command line. */
    void tell(String who, String text, String wire) {
        story.addFirst(new StoryLine(LocalTime.now().format(CLOCK), who, text, wire));
        while (story.size() > STORY_LENGTH) {
            story.pollLast();
        }
        if (options != null) {
            options.log(wire == null ? who + ": " + text : who + ": " + text + "    [" + wire + "]");
        }
    }

    /* one job at a time is what the lock is about, so how many were told they won is
       counted for each job and not across the whole run */
    void toldTheyWon(Long requestId) {
        toldTheyWon.computeIfAbsent(requestId, id -> new AtomicInteger()).incrementAndGet();
        lastJobId = requestId;
        tally("ACCEPTED");
    }

    public int mostWinnersOnOneJob() {
        return toldTheyWon.values().stream().mapToInt(AtomicInteger::get).max().orElse(0);
    }

    void tally(String outcome) {
        outcomes.computeIfAbsent(outcome, key -> new AtomicInteger()).incrementAndGet();
    }

    public int connectedCount() {
        int count = 0;
        for (VirtualMechanic m : fleet) {
            if (m.isConnected()) {
                count++;
            }
        }
        return count;
    }

    public boolean isRunning() {
        return running;
    }

    public Map<String, AtomicInteger> getOutcomes() {
        return outcomes;
    }

    public FleetState snapshot(int offerCount) {
        Map<String, Integer> outcomeMap = new ConcurrentHashMap<>();
        outcomes.forEach((k, v) -> outcomeMap.put(k, v.get()));

        List<MechanicState> mechanicStates = new ArrayList<>();
        int connected = 0;
        for (VirtualMechanic m : fleet) {
            mechanicStates.add(new MechanicState(m.name(), m.state(), m.doing()));
            if (m.isConnected()) {
                connected++;
            }
        }

        int targetSize = options != null ? options.count() : 0;
        Long latest = lastJobId;
        int latestWinners = latest == null ? 0 : toldTheyWon.getOrDefault(latest, new AtomicInteger()).get();
        return new FleetState(running, targetSize, connected, raceArmed, offerCount,
                outcomeMap, mechanicStates, new ArrayList<>(story), latest, latestWinners);
    }
}
