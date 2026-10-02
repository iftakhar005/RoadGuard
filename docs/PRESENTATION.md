# RoadGuard — Final Presentation Plan

About 10 minutes of talking plus a live demo. Full detail is in
`DOCUMENTATION.md`; this is the running order and what to say.

## 1. The problem (1 min)
"A driver breaks down. Today they call around and wait. RoadGuard sends one SOS
to every qualified mechanic nearby at once. The first to accept gets the job;
everyone else is told it is taken."

The hard part is not the map. It is correctness under concurrency: when four
mechanics tap Accept in the same millisecond, exactly one must win.

## 2. Architecture (1–2 min)
Browsers -> HTTP + WebSocket -> Spring Boot services -> database.
Services: Request, Dispatch, Assignment (the race), Matching, HeartbeatReaper,
OfferTimeout, AiTriage. Stack: Java 21, Spring Boot, JWT, JPA, WebSocket,
Leaflet + OpenStreetMap + OSRM, plain HTML/JS frontend.

## 3. One SOS, start to finish (3 min)
1. SOS saved as SEARCHING (or DIAGNOSING if a photo is attached)
2. AI triage reads the photo and picks specialization and severity; on any
   failure a safe default from the issue type is used
3. Matching: SQL bounding box, then Haversine, ranked by
   0.7 x distance + 0.3 x (1 - rating/5)
4. Broadcast to all matches at once
5. Accept race: one fair ReentrantLock per request, transaction commits inside
   the lock, version column as backstop, offer token rejects stale accepts
6. Lifecycle enforced server-side (EnumMap/EnumSet state machine)
7. Live position over WebSocket

## 4. When things go wrong (1–2 min)
- Mechanic's phone dies: heartbeat reaper, 15 s, job re-dispatched
- Nobody accepts: radius 5 -> 10 -> 20 -> 25 km, then ESCALATED
- Nobody online: same ladder; a mechanic going on duty revives the request
- AI down or slow: fallback, rescue never blocked

## 5. Live demo (3–5 min)
Follow section 9 of DOCUMENTATION.md. The two moments that matter:
- `./mvnw -B test -Dtest=AcceptRaceTest` (50 threads x 25 rounds)
- Close the mechanic window mid-job and show the re-dispatch

Prep: driver in a normal window, mechanic in incognito (shared local storage),
both logged in beforehand. Have screenshots or a recording as a backup.

## 6. Honest limits, then close (1 min)
Not built: raw TCP gateway, fleet simulator, admin dashboard, unsafe-mode
toggle, ratings, event replay. Browser GPS stops when the screen locks; a
native app would use the same API. AI triage accuracy has not been measured.

## Who says what
- Ifty: sections 3 (engine), 4, and the race test
- Sakib: sections 1, 2, 5 (demo), and the UI/real-time parts of 3
- Both: be able to summarise the other's half in one sentence

## Numbers you can quote
- 122 tests, 0 failures, 12 test classes (run on 2 Oct 2026)
- Race test: 50 racers x 25 repetitions = 1,250 accepts, one winner per round
  (`AcceptRaceTest`, `RACERS = 50`, `@RepeatedTest(25)`)

Do NOT quote the 4–13 ms WebSocket latency or the ETA match as fresh results
unless you re-measure them tonight; they come from earlier notes.
