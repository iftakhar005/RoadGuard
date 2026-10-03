package com.roadguard.sim;

import java.util.Map;
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

    public static void main(String[] args) throws Exception {
        Fleet options = Fleet.fromArgs(args);
        if (options == null) {
            return;
        }

        System.out.printf("Putting %d mechanics on the road, each on its own socket to %s:%d%n",
                options.count(), options.host(), options.port());
        if (options.race()) {
            System.out.println("Race mode: everyone offered a job accepts on the same countdown.");
        }
        System.out.println();

        FleetRunner runner = new FleetRunner();
        Registrar registrar = new Registrar(options);
        runner.start(options, registrar);

        System.out.printf("%n%d connected. Send an SOS near %.4f, %.4f and watch.%n",
                runner.connectedCount(), options.centreLat(), options.centreLng());
        System.out.println("Ctrl-C to bring them home.\n");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutDown(runner)));
        Thread.currentThread().join();
    }

    private static void shutDown(FleetRunner runner) {
        System.out.println("\nBringing the fleet home.");
        Map<String, AtomicInteger> outcomes = runner.getOutcomes();
        runner.stop();

        if (outcomes.isEmpty()) {
            System.out.println("Nothing was offered while they were out.");
            return;
        }

        System.out.println("\nWhat the gateway said back:");
        outcomes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> System.out.printf("  %-28s %d%n", entry.getKey(), entry.getValue().get()));

        int won = runner.mostWinnersOnOneJob();
        if (won > 1) {
            System.out.printf("%n%d mechanics won the same job. The accept lock was off.%n", won);
        } else if (won == 1) {
            System.out.println("\nOne winner, which is the whole point of the lock.");
        }
    }
}
