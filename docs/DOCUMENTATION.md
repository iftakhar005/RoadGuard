# RoadGuard — Project Documentation

**CSE 2118 · Advanced Object-Oriented Programming Lab**
Real-time roadside emergency dispatch platform

---

## 1. What RoadGuard is

A driver breaks down. They press one button. Every qualified mechanic nearby is
alerted **at the same time**, and the first to accept gets the job — the rest are
told it is taken.

That sentence is the whole project. It sounds simple and it is not: when four
mechanics tap *Accept* in the same millisecond, exactly one must win and the
other three must be told cleanly. Getting that right under real concurrency is
what this project is about. Everything else — maps, routing, shop profiles —
exists to make that engine visible.

---

## 2. Where the project stands

### Completed: the mandatory seven (100%)

| # | Feature | Evidence |
|---|---------|----------|
| 1 | Auth and roles (JWT; Driver, Mechanic, Admin) | `AuthService`, BCrypt, stateless |
| 2 | Mechanic availability toggle and location | `MechanicService` |
| 3 | SOS request with map pin, issue type, note | `RequestService.createSos` |
| 4 | Skill and distance matching, ranked | `MatchingService` |
| 5 | Broadcast dispatch, first-accept-wins | 1,250 concurrent accepts, exactly one winner, 25× |
| 6 | Real-time status over WebSocket | measured 4–13 ms |
| 7 | Request lifecycle state machine | `EnumMap` + `EnumSet`, enforced server-side |

### Completed: enhancements (3 of 6)

| Feature | Evidence |
|---------|----------|
| Live tracking and ETA | verified: 3.1 km → 1.1 km as the mechanic moved |
| Heartbeat disconnect and auto-reassign | verified live: job released 15 s after silence |
| Offer timeout and radius escalation | verified live: 5 → 10 → 20 → 25 km, then escalated |

### Built beyond the specification

Mechanic shop profiles with photo upload and map markers · editable skills ·
place search · animated SOS search radar · real road routing with ETA ·
password show/hide.

### Not yet built

| Gap | Why it matters |
|-----|----------------|
| **Raw TCP gateway (port 9090)** | The spec names this *the socket-programming component*. `app.tcp.port=9090` exists in config but no `ServerSocket` exists in the codebase. Currently scores zero. |
| **Fleet simulator** | Spec: *build early, required for demo and tests*. Would let us demo 50 racing mechanics without 50 phones. |
| **Admin dashboard** | `admin.html` is a 99-line placeholder reading "Coming next". No `/api/admin` endpoints exist. |
| **Unsafe-mode toggle** | `safeMode` already exists in `AssignmentService`; there is simply no way to flip it. |
| **Ratings** | `Rating` entity exists; no endpoint, no UI, no flow. |
| **AI fault triage** | The spec's named differentiator. Not started. |
| **Event log and replay** | `event_log` table exists; nothing reads it. |

**Overall: roughly 70% of the full specification.** The MVP is complete and the
hardest part, concurrency, is finished and proven.

### If time allows, in priority order

1. **Unsafe-mode toggle and a minimal admin page** — small, and it turns our
   strongest feature from *tested* into *demonstrable*
2. **Raw TCP gateway** — about 100 lines, closes a named course outcome
3. **Fleet simulator** speaking that TCP protocol
4. **Ratings** — entity exists, needs only endpoint and UI
5. AI triage, then event replay

---

## 3. Architecture at a glance

```
Browser (driver)        Browser (mechanic)       [future: TCP device]
      |                        |                          |
      +------ HTTP + WebSocket +--------------------------+
                         |
                 Spring Boot 3.5.3
                         |
  RequestService    DispatchService     AssignmentService
  (SOS intake)      (producer/consumer, (THE ACCEPT RACE,
                     priority queue)     per-request lock)

  MatchingService   HeartbeatReaper     OfferTimeoutService
  (rank candidates) (detect dropouts)   (widen / escalate)
                         |
                   MariaDB (JPA/Hibernate)
```

**Stack:** Java 21 · Spring Boot 3.5.3 · Spring Security 6 with JWT ·
JPA/Hibernate · MariaDB 10.4 · STOMP over SockJS · Leaflet with OpenStreetMap ·
OSRM routing · plain HTML/CSS/JS, no framework and no build step.

**13 service classes · 104 passing tests across 9 test classes.**

---

## 4. The database

The database is the single source of truth. Nothing important lives only in
memory, so a server restart loses nothing.

| Table | Holds | Notes |
|-------|-------|-------|
| `users` | account, email, BCrypt hash, role, phone | one row per person |
| `mechanic_profiles` | status, current lat/lng, last heartbeat, rating, shop details | one-to-one with a mechanic user |
| `mechanic_specializations` | which jobs a mechanic can take | element collection, several rows per mechanic |
| `service_requests` | origin, issue, severity, status, radius, attempts, assigned mechanic, version | the heart of the system |
| `request_offers` | every offer ever sent, with its outcome | the audit trail of the race |
| `request_offered_to` | who is in the current offer round | cleared each round |
| `ratings` | driver's score for a mechanic | entity exists, flow not built |
| `event_log`, `location_updates`, `vehicle_diagnoses` | reserved for replay and AI | tables exist, unused |
| `password_reset_tokens` | one-time reset codes | |

### Two columns worth explaining in the viva

**`service_requests.version`** — the optimistic-locking backstop. If two
transactions read the same request and both try to save, the second fails. This
is the second line of defence behind the lock.

**`current_offer_token`** — a UUID regenerated every offer round. A mechanic
accepting with an old token is rejected. Without it, a mechanic whose screen was
stale could accept a job that had already moved on.

### Why images are not in the database

`shop_image_path` stores only a filename in a `varchar(255)`; the file itself
lives in `uploads/shops/`. SQL can store binary with BLOB, but megabytes of
photos in rows bloat backups and compete for buffer-pool memory. The trade-off
is that files and rows can drift, so replacing a photo deletes the old file.

---

## 5. How the core works

### SOS: from button press to offers

1. Driver sets a pin, picks an issue type, presses **Send SOS**
2. `RequestService.createSos` saves the request as `SEARCHING`, deriving the
   required specialization from the issue type (`FLAT_TIRE` → `TIRE`)
3. After the transaction **commits**, the request id is pushed onto the dispatch
   queue — never before, or a worker could read a row that does not exist yet
4. A worker thread picks it up and calls `DispatchService.broadcast`

### Matching: who gets offered the job

Two stages, deliberately.

**Stage one, a cheap SQL filter.** A bounding box on latitude and longitude,
which an index can use, plus a status and skill check. This avoids doing
trigonometry for every mechanic in the country.

**Stage two, exact distance in Java.** Haversine great-circle distance on the
survivors; anything beyond the radius is dropped. The box is a square and the
radius is a circle, so this pass removes the corners.

Then ranking:

```
score = 0.7 × (distance / radius)  +  0.3 × (1 − rating/5)
```

Lower is better. Specialists are preferred over generalists; generalists are a
fallback only. Unrated mechanics score as a neutral 3.5 stars so a newcomer is
not buried.

**A design point worth defending:** we do not call a routing service when
ranking. That would be a paid network call per candidate, in the hot path. Cheap
mathematics ranks everyone; a real road route is fetched only for the one
mechanic who accepts.

### The accept race, the crown jewel

Three mechanics get the offer. All three tap Accept. Here is what happens:

```java
ReentrantLock lock = locks.computeIfAbsent(requestId, id -> new ReentrantLock(true));
lock.lock();
try {
    AcceptOutcome outcome = runAccept(requestId, mechanicUserId, offerToken);
    ...
} finally {
    lock.unlock();
}
```

Four things make this correct.

1. **One lock per request**, held in a `ConcurrentHashMap`, so two different
   breakdowns never block each other. A single global lock would serialise the
   whole system.
2. **A fair lock** (`new ReentrantLock(true)`) — first to arrive is first served,
   rather than arbitrary.
3. **The transaction commits inside the lock.** We use `TransactionTemplate`, not
   `@Transactional`. With `@Transactional` the lock would release before the
   commit landed, leaving a window where a second thread reads stale data. This
   is the subtlest part of the design.
4. **Version checking as a backstop** — if the lock were somehow bypassed, the
   database still refuses the second write.

Guards inside the attempt: status must be `OFFERED` or `REASSIGNING`, the request
must not already be assigned, the token must match the current round, the
mechanic must be in the offered set, the transition must be legal, and the
mechanic must be online.

**Proof:** `AcceptRaceTest` fires 50 threads released simultaneously by a
`CountDownLatch`, repeated 25 times. That is 1,250 accepts, with exactly one
winner every time.

### The lifecycle state machine

Legal transitions live in an `EnumMap` of `EnumSet`. Illegal moves are refused
server-side, so a tampered client cannot drag a job from searching straight to
completed.

```
CREATED -> SEARCHING -> OFFERED -> ACCEPTED -> EN_ROUTE -> ARRIVED -> IN_PROGRESS -> COMPLETED
               |           |           |
          ESCALATED   (no taker)   REASSIGNING -> SEARCHING
```

---

## 6. How tracking works

### Getting the mechanic's position

The browser's Geolocation API, `watchPosition`, which on a phone is the device's
fused GPS, Wi-Fi and cell location. Each fix is filtered before use:

- Accuracy worse than 120 m is ignored as noise and the last good position kept
- A move of less than 15 m is treated as jitter, not travel

This stops a phone on a dashboard twitching on the driver's map.

### Getting it to the driver, fast

When a mechanic's position is saved, the server publishes to that job's WebSocket
topic and the driver's page moves the marker straight from the message, with no
refetch. Measured across three moves: **13 ms, 4 ms, 4 ms.**

The route and ETA are throttled separately — re-fetched only if the mechanic
moved more than 150 m or the last route is more than 25 s old — so a mechanic
reporting every five seconds moves smoothly without hammering the routing
service.

### The route and ETA

Leaflet draws maps; it cannot find routes, because map tiles are pictures of
roads rather than a searchable network. Routing comes from OSRM, the public
router built on the same OpenStreetMap data as our tiles. No API key and no
account.

If OSRM is unreachable we fall back to a straight line, drawn dashed and labelled
as such, with a time from an average city speed, so the screen always says
something true.

**Verified:** the app reported 3.1 km and about 4 min; OSRM independently gives
3.06 km and 4.4 min for the same pair. Road distance 3.1 km against 1.9 km
straight line, so it really is following streets.

### An honest limitation

A web page stops receiving location when the screen locks or the tab is
backgrounded. Continuous background tracking needs a native app. This is a
browser rule rather than a flaw in our code, and the dispatch engine would not
change, since a native app would call the same REST API.

---

## 7. How recovery works

Three independent safety nets, all background threads on scheduled sweeps.

### A mechanic vanishes mid-job

Browsers send a heartbeat every five seconds. After fifteen seconds of silence
the reaper marks the mechanic offline **first**, and only then reads the jobs
they were holding and releases them. The order matters: doing it the other way
leaves a window where a job is attached to a mechanic already known to be gone.

Verified live: a request was released from its mechanic and re-queued fifteen
seconds after the phone went quiet.

### Nobody accepts

The timeout service sweeps every twenty seconds. A request with no taker doubles
its radius, up to a 25 km ceiling, and after the attempts are spent it escalates.

### Nobody is even there to ask

A request that finds zero candidates never reaches the offered state, so it used
to sit at 5 km for ever. Both failure modes now climb the same ladder. Verified
live:

```
00:21:31  SEARCHING  radius =  5 km
00:21:53  SEARCHING  radius = 10 km
00:22:16  SEARCHING  radius = 20 km
00:22:39  SEARCHING  radius = 25 km   <- capped
00:23:01  ESCALATED                   <- and it stops
```

### A mechanic comes back

Giving up is right on a timer, because a search that widens for ever is worse
than one that stops. But a mechanic coming on duty is new information: the reason
for giving up was that nobody was available, and that has just stopped being
true. So going on duty revives recently abandoned requests, resetting the
attempts and keeping the widened radius.

---

## 8. Who owns what — the 60/40 split

This divides the **system**, so each person owns a coherent area and can answer
any question inside it. Both should be able to describe the other's half in one
sentence.

### Person A — 60% · The Engine

Owns the backend: concurrency, persistence, security.

| Area | Files | Must be able to explain |
|------|-------|-------------------------|
| The accept race | `AssignmentService.java` | Why one lock per request; why the transaction commits inside the lock; what version checking catches; what the offer token prevents |
| Dispatch | `DispatchService.java` | Producer and consumer; why a priority queue ordered by severity; why enqueue happens after commit |
| Matching | `MatchingService.java`, `GeoUtils.java` | Bounding box then Haversine, and why that order; the 0.7/0.3 score; why not a routing API |
| Recovery | `HeartbeatReaper.java`, `OfferTimeoutService.java` | The 5 s and 15 s rule; why offline is set before jobs are read; the widening ladder and why it stops |
| State machine | `RequestStatus.java` | `EnumMap` and `EnumSet`; why transitions are enforced server-side |
| Persistence | entities and repositories | The schema; version columns; why images are files rather than BLOBs |
| Security | `SecurityConfig`, `AuthService`, the JWT filter | Stateless JWT; BCrypt; role rules; why secrets are git-ignored |
| Tests | 9 test classes, 104 tests | How a `CountDownLatch` creates a true race |

### Person B — 40% · The Experience

Owns everything the user sees, and the real-time client.

| Area | Files | Must be able to explain |
|------|-------|-------------------------|
| Driver experience | `driver.html`, `driver-sos.js`, `driver-map.js` | The SOS flow; the status wording; the pin |
| Mechanic console | `mechanic.html`, `mechanic-console.js` | Duty toggle; offer cards; the job stepper |
| Maps and geography UI | `sos-radar.js`, `pin-picker.js`, `route.js`, `job-map.js` | Why circles are drawn in metres rather than pixels; the radar; place search; the OSRM fallback |
| Real-time client | `live.js` | STOMP over SockJS; authentication on the connect frame; reconnection; why polling remains as a safety net |
| Shops and skills | `mechanic-shop.js`, `mechanic-skills.js`, `ShopService.java` | Upload validation; the path-traversal guard; why skills are editable |
| Design system | `style.css`, `console.css` | The colour language: blue is you, green is a mechanic, orange is a shop, red is the search |
| The demo | — | Driving the presentation and the backup plan |

### The seam between the halves

The two halves meet at the **REST API and the WebSocket topics**. Person A
guarantees the endpoints and the pushes; Person B consumes them. If asked how the
parts fit together, that is the answer.

---

## 9. The demo script, five minutes

**Before standing up:** driver in a normal browser window, mechanic in an
**incognito window** — both pages share local storage, so one will otherwise log
the other out. Log both in beforehand and never do login on stage.

| # | Show | Say |
|---|------|-----|
| 1 | Driver drags the pin, picks a flat tyre, sends the SOS | That red circle is the five kilometres we are searching right now |
| 2 | Mechanic window, offer already waiting | Every qualified mechanic nearby was alerted at once. That arrived in about a second |
| 3 | Accept; both screens change and the route appears | Real road distance from OSRM, not a straight line |
| 4 | Drag the mechanic pin, switch to the driver | Four milliseconds, over a WebSocket, not a refresh |
| 5 | Walk the job through its stages | Every transition is checked server-side against a state machine |
| 6 | **Run the race test live** | Twenty-five times, fifty mechanics accept the same job simultaneously. 1,250 accepts, exactly one winner, every time |
| 7 | **Close the mechanic window mid-job** | If a mechanic's phone dies the driver is not stranded. Fifteen seconds and it is re-dispatched |

Steps six and seven are the ones that matter. Step seven always lands, because it
is a failure being handled well.

The race test:

```bash
./mvnw -B test -Dtest=AcceptRaceTest
```

### Do not demonstrate

Phone GPS, which needs HTTPS, a lit screen and an outdoor signal · going offline
to recover an old job · place search or routing if the venue has no internet ·
the eighty-second radius ladder unless asked.

### If something breaks

Grey map tiles mean no internet; say the tiles come from OpenStreetMap and carry
on, because everything else is local. If no offer appears the mechanic was marked
offline, so toggle duty off and on and send a **fresh** SOS. For anything odd,
send a new SOS — fresh requests always work.

---

## 10. Questions the teacher will ask

**Why not just use Google Maps?**
Google Maps shows where things are. It does not decide who gets the job when four
mechanics tap accept in the same millisecond. The map is a library we call;
dispatch is what we built. We use OpenStreetMap and OSRM — the same capability,
with no API key and no billing account.

**Why broadcast instead of assigning one mechanic?**
It is how real dispatch works, and silent assignment to a mechanic who is away
from their phone creates dead requests. It also makes the concurrency real and
demonstrable.

**Why a lock per request rather than `synchronized`?**
A `synchronized` method on the service would serialise every breakdown in the
system. Per-request locks mean two different jobs never block each other, and
`ReentrantLock` also gives fairness, which `synchronized` does not.

**Why not rely on the database alone?**
We do keep version checking as a backstop. But relying on it alone means losers
discover they lost by catching an exception after doing the work. The lock makes
the common path clean and cheap.

**What happens if the mechanic's phone dies?**
Fifteen seconds of missed heartbeats and the reaper releases the job and
re-dispatches it. We can demonstrate that.

**Is this production-ready?**
No, and we can say exactly why: no admin dashboard, no ratings flow, background
location needs a native wrapper, and the TCP gateway for hardware devices is not
built. We know what is missing.

---

## 11. Running it

```
1. Start MariaDB     (XAMPP, or mysqld.exe directly)
2. Start the app     ./mvnw spring-boot:run
3. Open              http://localhost:8080
```

Secrets — the JWT key, database password and admin password — live in
`application-local.properties`, which is git-ignored; a template is committed.
The app also serves HTTPS on port 8443 from a locally generated certificate so a
phone on the same Wi-Fi can share its location, and that certificate is
git-ignored too.

**A known trap:** if `application-local.properties` is missing, the app silently
falls back to an H2 file database instead of MariaDB, with no error. A teammate
cloning the repository will see an empty database and no explanation. Worth
fixing.

---

*104 tests passing. Every measurement in this document was taken from the running
system, not estimated.*
