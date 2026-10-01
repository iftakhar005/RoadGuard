package com.roadguard.sim;

/** Everything the run was asked for, read once off the command line. */
public record Fleet(
        String api,
        String host,
        int port,
        int count,
        double centreLat,
        double centreLng,
        double spreadDegrees,
        double wanderDegrees,
        long tickMs,
        int minThinkMs,
        int maxThinkMs,
        boolean race,
        long raceGraceMs,
        boolean killOneMidJob,
        boolean declineInstead,
        boolean quiet) {

    public static Fleet defaults(int count, int port) {
        return new Fleet("http://localhost:8080", "localhost", port, count,
                23.8103, 90.4125, 0.018, 0.0016, 3000, 400, 2500,
                false, 600, false, false, false);
    }

    public Fleet withRace(boolean newRace) {
        return new Fleet(api, host, port, count, centreLat, centreLng, spreadDegrees, wanderDegrees,
                tickMs, minThinkMs, maxThinkMs, newRace, raceGraceMs, killOneMidJob, declineInstead, quiet);
    }

    public Fleet withCount(int newCount) {
        return new Fleet(api, host, port, newCount, centreLat, centreLng, spreadDegrees, wanderDegrees,
                tickMs, minThinkMs, maxThinkMs, race, raceGraceMs, killOneMidJob, declineInstead, quiet);
    }

    public static Fleet fromArgs(String[] args) {
        String api = "http://localhost:8080";
        String host = "localhost";
        int port = 9090;
        int count = 8;
        double lat = 23.8103;
        double lng = 90.4125;
        double spread = 0.018;
        double wander = 0.0016;
        long tick = 3000;
        int minThink = 400;
        int maxThink = 2500;
        boolean race = false;
        long grace = 600;
        boolean kill = false;
        boolean decline = false;
        boolean quiet = false;

        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            switch (flag) {
                case "--race" -> race = true;
                case "--kill" -> kill = true;
                case "--decline" -> decline = true;
                case "--quiet" -> quiet = true;
                case "--help", "-h" -> {
                    usage();
                    return null;
                }
                default -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing a value after " + flag);
                    }
                    String value = args[++i];
                    switch (flag) {
                        case "--api" -> api = value;
                        case "--host" -> host = value;
                        case "--port" -> port = Integer.parseInt(value);
                        case "--count" -> count = Integer.parseInt(value);
                        case "--lat" -> lat = Double.parseDouble(value);
                        case "--lng" -> lng = Double.parseDouble(value);
                        case "--spread" -> spread = Double.parseDouble(value);
                        case "--wander" -> wander = Double.parseDouble(value);
                        case "--tick" -> tick = Long.parseLong(value);
                        case "--think" -> {
                            String[] range = value.split("-");
                            minThink = Integer.parseInt(range[0]);
                            maxThink = range.length > 1 ? Integer.parseInt(range[1]) : minThink + 1;
                        }
                        case "--grace" -> grace = Long.parseLong(value);
                        default -> throw new IllegalArgumentException("I do not know the flag " + flag);
                    }
                }
            }
        }

        return new Fleet(api, host, port, count, lat, lng, spread, wander,
                tick, minThink, maxThink, race, grace, kill, decline, quiet);
    }

    static void usage() {
        System.out.println("""
                Fleet simulator - opens one real TCP socket per pretend mechanic.

                  --count N        how many mechanics to put on the road (default 8)
                  --host H         gateway host (default localhost)
                  --port P         gateway port (default 9090)
                  --api URL        where the REST API is, for signing the mechanics up
                                   (default http://localhost:8080)
                  --lat / --lng    centre of the fleet (default Dhaka)
                  --spread D       how far around the centre they scatter, in degrees
                  --wander D       how far each one drifts per tick, in degrees
                  --tick MS        how often they report position (default 3000)
                  --think A-B      how long they dither before accepting (default 400-2500)
                  --race           everyone offered a job accepts at the same instant
                  --grace MS       how long to gather offers before the race starts
                  --decline        decline instead of accepting, to watch it move on
                  --kill           whoever wins a job then drops off the network
                  --quiet          only print the summary

                The race is only as wide as app.dispatch.offer-count lets it be: the
                server offers a job to that many mechanics and nobody else can accept
                it. Raise it to see a wide race.
                """);
    }

    void log(String message) {
        if (!quiet) {
            System.out.println("  " + message);
        }
    }
}
