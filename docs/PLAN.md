# RoadGuard: what is left and how to build it

Written 1 Oct 2026 against `ai-triage` at `d0434eb` (152 tests, 0 failures). Everything
below was checked against the code, not remembered. Where a line says "verify", it is
something I could not confirm without running the app.

## Where things stand

Done and pushed: dispatch with the accept lock and offer queue, heartbeat reaper, offer
timeout, radius escalation, AI triage (driver's choice decides the trade, the photo only
sets severity), chat, admin dashboard with the unsafe-mode switch, the TCP gateway on 9090.

Four things remain from the spec, in this order of importance:

| # | Item | Spec | State today |
|---|------|------|-------------|
| 1 | Fleet simulator, shown from the admin console | 12 | Four files in `src/main/java/com/roadguard/sim/`. Compiles. Never run. Uncommitted. |
| 2 | Ratings | 4.2.11 | `Rating` entity, `RatingRepository` and `MechanicProfile.addRating()` exist. Nothing calls them. Every mechanic ranks on the neutral 3.5. |
| 3 | Event log, replay, file I/O | 14 | `EventLog` and `LocationUpdate` entities and repositories exist. Nothing writes either. No `/replay` endpoint. |
| 4 | Documentation, demo script, rehearsal | n/a | `docs/DOCUMENTATION.md` predates the gateway, the admin dashboard and the simulator. |

## Rules that apply to every step

- **Commit as the user only. No `Co-Authored-By` trailer**, whatever the tooling suggests.
- Secrets stay out of git. `application-local.properties` is ignored. The committed
  `.example` must keep a blank Gemini key. `uploads/` and `certs/` are ignored; add
  `logs/` and `replays/` in step 3.
- Code should read as hand-written and match its surroundings: short comments only where
  the reason is not obvious, no banner comments, no tool-generated markers.
- Windows. PowerShell 5.1 has no `&&`; use `;` and `.\mvnw.cmd`.
- The machine has little memory. Before building: kill stray `java` and `ngrok`, then
  `MAVEN_OPTS=-Xmx512m` and `-DargLine="-Xmx700m -XX:MaxMetaspaceSize=256m"`. A
  "forked VM terminated" error means out of memory, not a failing test.
- Delete `target/surefire-reports` before counting tests, or removed tests keep being counted.
- `spring-boot:run` serves static files from `target/classes/static`. After editing
  anything under `src/main/resources/static`, copy it across or the browser sees the old file.
- The test suite does not go through HTTP. Controllers run with `open-in-view=false`, so
  touching a lazy association there throws `LazyInitializationException` and returns 500
  while every test stays green. This already happened once. Every new endpoint gets one
  real `curl` against the running app before it counts as done.
- The app refuses to start on H2 (`DatabaseCheck`). MariaDB must be up first. XAMPP is at
  `E:\new folder\mysql`; start it with
  `mysqld.exe --defaults-file="E:\new folder\mysql\bin\my.ini" --standalone` and give it
  a minute to recover after an unclean shutdown. Test profile sets
  `app.db.allow-throwaway=true`; do not copy that into local properties.
- Commit small, push after each commit (retry once on a DNS error). After each finished
  step, write three or four sentences on what it does and why, so the user can explain it
  to the teacher.

## Step 0: get the stack running and prove the simulator works

1. Start MariaDB, then the app. The startup log must say `Using MariaDB` and
   `Device gateway listening on 9090`.
2. Run the simulator as a separate process. Either build a classpath file once:

   ```
   .\mvnw.cmd -q dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
   java -cp "target/classes;$(Get-Content target/cp.txt)" com.roadguard.sim.FleetSimulator --count 3
   ```

   or package and use `PropertiesLauncher` with `-Dloader.main=com.roadguard.sim.FleetSimulator`.
   Verify whichever you use.
3. Things in the simulator I am not sure of, check each:
   - `Registrar` sends `specializations` and `phone` to `/api/auth/register`. Confirm the
     endpoint accepts all eight skills for a MECHANIC and that the phone format passes
     validation. If registration fails, fix `Registrar`, not the server.
   - On `AUTH_OK` each mechanic sends `LOC`, which flips it from OFFLINE to ONLINE. Confirm
     the admin map shows three pins.
   - Send one SOS from a driver account near 23.81, 90.41. With `--think 400-1500` one
     mechanic should win, the others should get `TAKEN`, and the summary on Ctrl-C should
     show one `ACCEPTED`.
4. **Race width.** The server offers a job to `app.dispatch.offer-count` mechanics (3) and
   only those can accept it, so `--race --count 20` is still a three-way race. For the
   demo set `app.dispatch.offer-count=10` in `application-local.properties` (not the
   committed file) and run with `--count 12`.
5. **Fix a gap in the gateway first.** The REST path `MechanicService.updateLocation`
   calls `tellWhoeverIsWaiting`, which pushes `MOVED` to the driver's map. The gateway's
   `TcpGateway.location()` writes the profile and stops, so a simulated mechanic on a job
   looks frozen to the driver. Pull the "tell whoever is waiting" part into something both
   paths call, add a test that a `LOC` over the socket produces a `MOVED` for the assigned
   job, then commit.
6. Commit `sim/` once steps 2 to 4 pass.

## Step 1: the fleet as an admin feature

Goal: the admin starts a fleet from the dashboard, sees the pins appear on the existing
map, fires the race, and reads the result. The command-line version stays, because it is
the one that is plainly a separate client and answers "is this just the server talking
to itself".

### Design

- Split `FleetSimulator` so its logic is not welded to `main`. A `FleetRunner` owns
  start(count), stop(), arm the race, drop the winner, and a snapshot of state. `main`
  becomes a thin wrapper that builds `Fleet` options from the command line and calls it.
- A Spring `@Service` (say `FleetControl`) holds one `FleetRunner` and is what the admin
  endpoints talk to. It connects to the gateway on loopback with real sockets. That is
  fine, but say so in the panel text rather than let anyone assume otherwise.
- `Registrar` currently signs up over HTTP. In-process, use `AuthService` directly
  instead of calling itself over the network. Put an `AccountSource` interface in front
  of both and keep the HTTP one for the command line.
- Simulated accounts are identified by the username prefix `sim_mech_`. Keep that as one
  constant. No schema change.

### Endpoints (all under `/api/admin`, which `SecurityConfig` already restricts to ADMIN)

- `GET  /fleet` returns `{running, size, connected, raceArmed, offerCount, outcomes, mechanics:[{name, state}]}`
- `POST /fleet/start` with `{count}` (cap it, 25 is plenty)
- `POST /fleet/stop`
- `POST /fleet/race` with `{armed}`
- `POST /fleet/drop-winner`

Starting twice must not double the fleet. Stopping must leave every simulated mechanic
OFFLINE (the gateway already does that on a clean `BYE`; add a test).

### Panel

One card next to the accept-lock card, same markup and `console.css` classes. Controls:
count, Start fleet, Stop, an "Everyone accepts at once" toggle, Drop the winner. Beside
them a result line that says how many mechanics won the last job, driven by the `ACCEPTED`
tally. Lock on should read 1. Lock off should read more than 1, and the line should say
the lock was off.

Pins need no new code: the admin map already draws every mechanic with a location,
coloured by status.

Put a plain warning on the card: while the fleet runs, a real driver's SOS can be
offered to simulated mechanics. It is not a bug, but it has to be visible.

### Tests

- Start, then wait for `connected == size`, then stop and assert every `sim_mech_%`
  profile is OFFLINE.
- Race with the lock on: one winner, rest told `ALREADY_TAKEN` or `TAKEN`. Run it 50 times
  in a loop; the existing `AcceptRaceTest` is the model.
- Race with the lock off: more than one `ACCEPTED` at least once in a bounded number of
  tries. This one is probabilistic, so assert on "at least one run produced two winners"
  across N runs, not on a single run.
- Drop the winner: the job returns to SEARCHING or OFFERED within heartbeat timeout plus
  one reaper sweep.
- Non-admin gets 403 on every `/fleet` endpoint.

### Done when

Start 12 from the browser, see 12 pins, send an SOS from a phone, fire the race, read "1 won",
flip the lock off, run again, read "2 won", flip it back. Then drop the winner and watch
the job move.

## Step 2: ratings

The data model is already there; this is the missing write path and the UI.

- `RatingService.rate(driver, requestId, stars, comment)` in one transaction:
  - request must exist and be **COMPLETED**
  - the caller must be that request's driver
  - stars between 1 and 5, comment at most 500 characters
  - one rating per request: `ratings.request_id` is unique, so catch the constraint
    violation as well as checking first, because two taps can race
  - save the `Rating`, then call `profile.addRating(stars)` on the assigned mechanic
- `addRating` is a read-modify-write on the profile. `MechanicProfile` has `@Version`, so
  two ratings landing together will not lose one silently: the second throws an
  optimistic-lock failure. Either retry on that, or recompute the average and count from
  the `ratings` table inside the transaction, which cannot drift and needs no retry.
  Recomputing is the simpler of the two. Test two ratings for the same mechanic from
  different requests at the same moment.
- `POST /api/requests/{id}/rating` with `{stars, comment}`. Add a `rated` flag (and the
  stars given) to `ServiceRequestResponse` so the UI knows whether to ask.
- Driver UI: after a request reaches COMPLETED and is not yet rated, show a dialog with
  five stars and an optional comment. `driver-sos.js` is where the live status is handled.
  Match the existing white-card styling; the user has rejected several chat designs for
  not matching, so look at the app before inventing one.
- Show the average and count on the mechanic's own console and keep the shop card as is.
- The matcher already uses it (`MatchingService.score`, weight 0.3 against 0.7 for
  distance). No change needed. Add a test proving it matters: two mechanics the same
  distance away, one rated 5.0 over several jobs and one rated 2.0, and the better one is
  offered first. `MatchingServiceTest` is the model.

Tests: rate once, rate twice (rejected), rate before completion (rejected), rate someone
else's request (rejected, 403), stars 0 and 6 (rejected), average maths over three
ratings, the ranking test above. One real `curl` for the endpoint.

## Step 3: event log, replay and file I/O

The spec wants this to show secondary storage and serialization, so the files matter, not
only the table.

### Recording

- `EventRecorder.record(requestId, type, payloadJson)` does two things: saves an
  `EventLog` row and appends one JSON line to `logs/request-{id}.jsonl`. A failure in
  either (unwritable directory, full disk) logs a warning and **never** fails the request.
  Test that explicitly.
- Record on: SOS created, offers sent, offers closed, accepted, each status change,
  cancelled, round expired, escalated, released from a mechanic (reassigning), completed.
  `RealtimeNotifier` is already called from nearly all of these, so recording from there
  needs the fewest edits. The cleaner alternative is Spring events, which the gateway
  already uses. Pick one and keep it consistent.
- Location samples while a job is on: write a `LocationUpdate` row (the entity that is
  unused today) and an event line. **Throttle to one sample per request per two seconds**
  or twelve simulated mechanics will flood the table. Do it for both the REST and the
  gateway path, which is why step 0 item 5 comes first.
- Add `logs/` and `replays/` to `.gitignore`.

### Snapshot

When a request reaches COMPLETED or CANCELLED, build a `ReplaySnapshot` (serializable: the
ordered events and the route trail) and write it with `ObjectOutputStream` to
`replays/request-{id}.ser`. Give the class a `serialVersionUID`. Reading it back must give
an equal object; test the round trip.

### Replay

- `GET /api/requests/{id}/replay` returns `[{t, type, lat, lng, status}]` in order.
  Prefer the snapshot file when it exists, otherwise rebuild from `EventLog`. Allowed for
  the request's driver, its assigned mechanic, and admin; 403 otherwise.
- Do not navigate lazy associations in the controller (see the rules above).
- Front end on its **own page**, `replay.html` and `js/replay.js`, so it does not collide
  with the fleet panel in `admin.html`. A Leaflet map, the mechanic's path drawn as it
  advances, status changes announced as they happen, and a speed control (1x, 4x, 16x).
  Link to it from the admin request rows and from the driver's completed jobs.

### Tests

A full job (create, offer, accept, en route, arrived, in progress, completed) produces the
expected events in order, the `.jsonl` has the same number of lines as the table has rows,
the snapshot round-trips, the replay endpoint returns the same order, and an unwritable
`logs` directory does not break the job.

## Step 4: documentation and rehearsal

- Update `docs/DOCUMENTATION.md`: the gateway, the admin dashboard, the fleet simulator,
  ratings, replay. Redo the 60/40 split to include them.
- Add the new settings to `application-local.properties.example` with blank values.
- Replace the old demo script with this one:
  1. Start the fleet from the admin page. Pins appear.
  2. Send an SOS from a phone. One mechanic wins, the others see "already taken".
  3. Race with the lock on, then off, then on. One winner, two winners, one winner.
  4. Drop the winner. The reaper notices and the job moves to someone else.
  5. Complete a job, rate it, open the replay.
  6. Run the command-line simulator from a second terminal to show it is a separate
     process.
  7. `telnet localhost 9090`, type `SANDWICH please`, show the parser refusing it.
- Questions to be ready for: why two transports (browsers cannot open raw sockets, so the
  chat and map use WebSocket and the gateway uses `ServerSocket`); why the lock and the
  version column both exist; what happens if a mechanic's process dies (heartbeat reaper);
  why the driver's choice outranks the photo.
- Rehearse the whole demo at least three times on the machine and network it will run on,
  and record a backup video.

## Splitting the work between two agents

Branch each from `ai-triage` and merge in the order below. These sets of files do not
overlap, which is the point.

**Agent A: simulator and admin.** Step 0, step 1. Touches `sim/`, `tcp/TcpGateway`,
`service/MechanicService` (only the shared location helper), `web/AdminController`,
`admin.html`, `admin.js`, `console.css`.

**Agent B: ratings, then replay.** Step 2, step 3. Touches `RatingService` and its
controller method, `RequestController`, `ServiceRequestResponse`, `driver-sos.js`,
`EventRecorder`, `RealtimeNotifier`, `AssignmentService`, `replay.html`, `replay.js`.

**Shared edge:** step 3 needs the location helper from step 0 item 5. Agent A merges that
commit first and says so. `RealtimeNotifier` is the only file both will want; keep
Agent A's edits to it to zero.

Merge A, then B's ratings, then B's replay. Run the whole suite after each merge. The
baseline to beat is 152 passing.

## Risks worth knowing about

- The fleet shares the real database. Simulated mechanics can win real jobs while it runs.
- A wide race needs `offer-count` raised; otherwise the demo understates the lock.
- The lock-off race is probabilistic. Never script a demo that depends on it happening on
  the first try; fire it a few times and say so.
- Gemini is live in the local profile. Replaying photos spends quota and the model has
  already misread one fuel photo as an engine fault. Routing now ignores it, severity does not.
- MariaDB comes up slowly after an unclean shutdown. Start it a few minutes before the show.
