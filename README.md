# RoadGuard

Real-time roadside emergency dispatch: a driver reports a breakdown, nearby qualified mechanics are alerted at once, and the first to accept gets the job. RoadGuard combines a REST API, browser portals, live WebSocket updates, and a raw TCP gateway for mechanic devices and a virtual fleet.

## Features

- **Accounts and roles** — JWT authentication with `DRIVER`, `MECHANIC`, and `ADMIN` roles; BCrypt password hashing; six-digit password reset codes; admin account seeded at startup from configuration.
- **SOS intake** — map pin, issue type, note, optional photo, and AI fault triage with a safe stub fallback when no Gemini key is configured.
- **Skill and distance matching** — bounding-box filter, haversine distance, specialization preference, and a combined distance/rating score (`0.7 × distance + 0.3 × rating penalty`).
- **Concurrent dispatch** — priority queue by severity, batched offers, and a fair per-request accept lock with JPA optimistic locking as a backstop, so exactly one mechanic wins a race.
- **Live job lifecycle** — `SEARCHING → OFFERED → ACCEPTED → EN_ROUTE → ARRIVED → IN_PROGRESS → COMPLETED`, with server-side enforcement of legal transitions.
- **Real-time maps and chat** — STOMP over SockJS for offers, status changes, location updates, and chat; Leaflet + OpenStreetMap; OSRM road routing with a straight-line fallback.
- **Automatic recovery** — heartbeat reaping after 15 seconds of silence, offer timeouts with radius widening (5 → 10 → 20 → 25 km), escalation, and revival of recently escalated requests when a mechanic comes on duty.
- **Mechanic profiles** — availability, live location, editable skills, shop details with photo upload, and driver ratings that feed back into matching.
- **Admin console** — live metrics and map, request re-dispatch, an accept-lock toggle for demonstrating race collisions, and a controllable fleet of simulated mechanics.
- **Event log and replay** — append-only JSONL events, serialized snapshots, and an animated `replay.html` timeline viewer.
- **Raw TCP device gateway** — a line-based protocol on port `9090` used by real devices and the fleet simulator, sharing the same services and locking rules as the browser API.

## Tech stack

| Layer | Technology |
| --- | --- |
| Backend | Java 21, Spring Boot 3.5.3, Spring MVC, Spring Security, Spring Data JPA/Hibernate, Spring WebSocket, Spring Mail, Bean Validation |
| Security | Stateless JWT (JJWT 0.12.6), BCrypt, role-based access control |
| Database | H2 (throwaway/test) or MariaDB/MySQL via JDBC |
| Real time | STOMP over SockJS (`/ws`), raw `ServerSocket` TCP gateway (`:9090`) |
| Frontend | Plain HTML, CSS, JavaScript; Leaflet 1.9.4, SockJS, STOMP.js; no npm build step |
| External services | OpenStreetMap tiles, OSRM routing, Nominatim place search, optional Gemini Vision API |
| Tests | JUnit 5, Spring Boot Test, Spring Security Test |
| Build | Maven wrapper (Maven 3.9.16), Spring Boot Maven plugin |

## Architecture

```text
Driver portal ─┐
Mechanic console ─┼─ HTTP/REST + STOMP over SockJS ─┐
Admin dashboard ─┘                                  ├─ Spring Boot application
Replay viewer ───── HTTP/REST ──────────────────────┤
Virtual fleet / devices ─ raw TCP :9090 ────────────┘
                                                     │
              RequestService · DispatchService · AssignmentService
              MatchingService · HeartbeatReaper · OfferTimeoutService
              AiTriageService · ChatService · RatingService
              EventRecorder · ReplayService · TcpGateway · FleetControl
                                                     │
                     JPA database ─┬─ logs/*.jsonl ─┬─ replays/*.ser
                                   └─ uploads/       └─ optional SMTP / Gemini
```

The dispatch worker reads requests after their database transaction commits, ranks online mechanics, and sends a batch of offers. Each accept attempt takes a fair lock keyed by request id; the transaction runs and commits inside that lock. A fresh offer token per round and a JPA `@Version` column prevent stale or duplicate accepts.

The browser cannot open a raw TCP socket, so browsers use WebSocket/REST while devices and the simulator use the TCP gateway. Both paths call the same services, so they race under the same rules.

## Project layout

```text
src/main/java/com/roadguard/
  config/       Security-adjacent startup config, WebSocket, HTTPS, admin seeder, DB check
  domain/       JPA entities; domain/enums/ holds statuses, roles, skills, severities
  repository/   Spring Data repositories
  security/     JWT service/filter, user details, security rules
  service/      Dispatch, matching, assignment, chat, ratings, replay, recovery, AI
  sim/          Virtual mechanics, fleet runner, CLI simulator
  tcp/          Raw TCP gateway and its Spring application events
  web/          REST controllers, global error handler, request/response DTOs
src/main/resources/
  application.properties               Base settings (local profile active by default)
  application-local.properties.example Template for secrets and DB settings
  static/                              HTML pages, CSS, JavaScript, demo video
src/test/                              Unit and integration tests
docs/                                  Detailed design/demo notes and project report
logs/, replays/, uploads/, certs/      Local runtime artifacts (git-ignored)
```

## Requirements

- JDK 21
- Maven wrapper included in the repository (`.\mvnw.cmd` on Windows, `./mvnw` on macOS/Linux)
- A reachable MariaDB or MySQL database unless throwaway H2 mode is explicitly enabled
- Internet access for CDN assets, map tiles, OSRM routing, Nominatim search, and optional Gemini triage

## Quick start

### 1. Configure the local profile

`application.properties` activates the `local` profile by default. Copy the template and edit it:

```powershell
Copy-Item src\main\resources\application-local.properties.example `
          src\main\resources\application-local.properties
```

Fill in at least:

```properties
app.jwt.secret=a-long-random-secret-of-your-own
app.jwt.expiry-hours=24

app.admin.username=admin
app.admin.email=admin@roadguard.local
app.admin.password=choose-a-password

# Point this at your database (MariaDB/MySQL drivers are bundled):
spring.datasource.url=jdbc:mariadb://localhost:3306/roadguard?createDatabaseIfNotExist=true
spring.datasource.driver-class-name=org.mariadb.jdbc.Driver
spring.datasource.username=root
spring.datasource.password=

# Keep the offline AI stub unless you have a Gemini key:
app.ai.provider=stub
app.gemini.api-key=
```

`application-local.properties` is git-ignored. Never commit real JWT secrets, admin passwords, or API keys.

To try the app without a real database, set `app.db.allow-throwaway=true` in the local file. By default the app **refuses** to start on the fallback H2 file database, because an empty database looks exactly like every account having been deleted. The `test` profile permits H2 automatically.

### 2. Start the application

```powershell
# Windows
.\mvnw.cmd spring-boot:run
```

```bash
# macOS/Linux
./mvnw spring-boot:run
```

The startup log should name the database in use. If the TCP gateway is enabled it also listens on port `9090`.

### 3. Open the portals

| URL | Purpose |
| --- | --- |
| `http://localhost:8080/` | Sign in / register / reset password |
| `http://localhost:8080/driver.html` | Driver: pin location, send SOS, track mechanic, chat, rate, replay |
| `http://localhost:8080/mechanic.html` | Mechanic: duty toggle, offers, job stepper, location, shop, skills |
| `http://localhost:8080/admin.html` | Admin: overview, live map, fleet controls, accept-lock toggle |
| `http://localhost:8080/replay.html?id=1` | Incident replay for a completed request |
| `http://localhost:8080/h2-console` | H2 console (only when using H2) |

Registration cannot create an `ADMIN`; use the credentials seeded from `app.admin.*`.

### 4. Run the tests

```powershell
.\mvnw.cmd test
```

```bash
./mvnw test
```

Most integration tests run under the `test` profile with an in-memory H2 database, stub AI, and an ephemeral TCP port. On low-memory machines, reduce Maven/test heap sizes (for example `MAVEN_OPTS=-Xmx512m` and `-DargLine="-Xmx700m -XX:MaxMetaspaceSize=256m"`).

> Static files are served from `target/classes/static`. After editing files under `src/main/resources/static`, rebuild or copy them across before reloading the browser.

## Configuration reference

Base values in `application.properties`; override them in `application-local.properties`.

| Property | Default | Meaning |
| --- | --- | --- |
| `server.port` | `8080` | HTTP, static pages, REST API, `/ws` |
| `spring.profiles.active` | `local` | Activates the git-ignored local configuration |
| `spring.datasource.*` | H2 file DB | Real settings belong in the local profile |
| `app.db.allow-throwaway` | `false` | Permit startup on H2 outside the `test` profile |
| `app.jwt.secret` / `app.jwt.expiry-hours` | — / `24` | JWT signing key and token lifetime |
| `app.admin.username` / `.email` / `.password` | — | First admin account, seeded if absent |
| `app.dispatch.default-radius-km` / `.max-radius-km` | `5` / `25` | Search radius and cap |
| `app.dispatch.offer-count` / `.urgent-offer-count` | `3` / `5` | Mechanics offered per round |
| `app.dispatch.offer-timeout-sec` / `.max-attempts` | `20` / `3` | Round timeout and widening attempts |
| `app.dispatch.worker-threads` | `4` | Dispatch worker count |
| `app.heartbeat.interval-sec` / `.timeout-sec` | `5` / `15` | Client heartbeat and offline threshold |
| `app.tcp.port` / `.workers` | `9090` / `64` | Device gateway port and handler pool |
| `app.ai.provider` | `stub` | `stub` or `gemini` (requires `app.gemini.api-key`) |
| `app.ai.diagnosing-timeout-sec` | `30` | Time allowed for a photo diagnosis |
| `app.events.log-dir` / `.replay-dir` | `logs` / `replays` | Event log and snapshot directories |
| `app.uploads.dir` | `uploads` | SOS, chat, and shop image storage |
| `app.https.port` / `.keystore` | `8443` / `certs/roadguard.p12` | Optional HTTPS connector |
| `app.demo.allow-unsafe` | `true` | Allows the admin UI to disable the accept lock for demos |

### HTTPS for phone testing

Browsers only share geolocation over HTTPS or localhost. If a PKCS#12 keystore exists at `certs/roadguard.p12`, Spring Boot adds a second connector on port `8443`. For local testing you can generate one yourself:

```powershell
keytool -genkeypair -alias roadguard -keyalg RSA -keysize 2048 `
  -storetype PKCS12 -keystore certs\roadguard.p12 -storepass roadguard -validity 365
```

The certificate directory is git-ignored. Without it the app serves plain HTTP on `8080` and logs a warning.

## REST API

All application endpoints are under `/api`. Send `Authorization: Bearer <jwt>` on protected calls. The global error handler returns `{status, error, message}` with 400/401/403/409 as appropriate.

| Group | Endpoints | Access |
| --- | --- | --- |
| Auth | `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/forgot-password`, `POST /api/auth/reset-password`, `GET /api/auth/me` | Public (send a token for `/me`) |
| Requests | `POST /api/requests`, `GET /api/requests/mine`, `GET /api/requests/mine-assigned`, `GET /api/requests/{id}`, `POST /api/requests/{id}/photo`, `GET /api/requests/{id}/photo`, `POST /api/requests/{id}/accept`, `POST /api/requests/{id}/decline`, `POST /api/requests/{id}/status`, `POST /api/requests/{id}/cancel` | Authenticated; driver/mechanic ownership checked in services |
| Requests (extra) | `GET /api/requests/{id}/candidates`, `POST /api/requests/{id}/rating`, `GET /api/requests/{id}/chat`, `GET /api/requests/{id}/chat/media/{name}`, `GET /api/requests/{id}/replay` | Driver, assigned mechanic, or admin as applicable |
| Mechanic | `GET /api/mechanic/me`, `POST /api/mechanic/status`, `POST /api/mechanic/location`, `POST /api/mechanic/heartbeat`, `POST /api/mechanic/skills`, `GET|POST /api/mechanic/shop`, `POST /api/mechanic/shop/image`, `GET /api/mechanic/offers` | `ROLE_MECHANIC` |
| Nearby | `GET /api/mechanics/nearby?lat=&lng=&radiusKm=` | Authenticated; online mechanics only |
| Shops | `GET /api/shops`, `GET /api/shops/{id}`, `GET /api/shops/{id}/image` | Shop image is public; other reads authenticated |
| Admin | `GET /api/admin/overview`, `POST /api/admin/safe-mode`, `POST /api/admin/requests/{id}/redispatch`, `GET /api/admin/fleet`, `POST /api/admin/fleet/start`, `POST /api/admin/fleet/stop`, `POST /api/admin/fleet/race`, `POST /api/admin/fleet/drop-winner` | `ROLE_ADMIN` |

The accept endpoint responds `200` with `won: true` to the winner and `409` with an outcome such as `ALREADY_TAKEN` or `OFFER_EXPIRED` to losers. Status updates are limited to `EN_ROUTE`, `ARRIVED`, `IN_PROGRESS`, and `COMPLETED`.

## WebSocket topics

Connect to `/ws` with SockJS and include `Authorization: Bearer <jwt>` as a native CONNECT header.

| Destination | Direction | Content |
| --- | --- | --- |
| `/user/queue/updates` | server → client | `OFFER`, `OFFER_CLOSED`, `STATUS`, mechanic summaries |
| `/topic/request/{id}` | server → client | `STATUS` and `MOVED` for one job |
| `/topic/chat/{id}` | server → client | New chat lines for one job |
| `/topic/admin` | server → client | Live admin status summaries |
| `/app/chat.send` | client → server | `{requestId, text, mediaDataUrl}` |

`live.js` reconnects automatically every three seconds. The driver and admin pages also poll REST endpoints as a fallback.

## Raw TCP device protocol

Connect to `localhost:9090` and send one command per line:

```text
HELLO <mechanicUserId> <jwt>
LOC <lat> <lng>
HEARTBEAT
ACCEPT <requestId> <offerToken>
DECLINE <requestId> [offerToken]
STATUS <requestId> <EN_ROUTE|ARRIVED|IN_PROGRESS|COMPLETED>
BYE
```

The server replies with `AUTH_OK`/`AUTH_FAIL`, `OK`, `ACCEPTED <id>` or an outcome name, and `STATUS_OK`/`STATUS_REFUSED`. It can push `OFFER <id> <token> <issue> <severity> <km> <lat> <lng>`, `TAKEN <id>`, and `ASSIGNED <id>` on the same connection. Invalid input receives `ERR ...`; for example `telnet localhost 9090` followed by `SANDWICH please` demonstrates the parser's rejection path.

## Fleet simulator

The admin dashboard can start up to 25 simulated mechanics, each holding a real TCP connection to the gateway. It can arm a synchronized race (everyone accepts at once), count the winners, and drop the winner so the heartbeat reaper reassigns the job. Raise `app.dispatch.offer-count` (for example to `10`) for a wider race; otherwise only the default number of mechanics can accept.

A standalone CLI process is also available for demonstrations:

```powershell
.\mvnw.cmd -q dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
java -cp "target/classes;$(Get-Content target/cp.txt)" com.roadguard.sim.FleetSimulator --count 8 --race
```

Useful flags include `--help`, `--count`, `--host`, `--port`, `--lat`, `--lng`, `--spread`, `--think A-B`, `--race`, `--grace`, `--decline`, `--kill`, `--drive 20`, `--stay`, and `--quiet`. Simulator accounts use the `sim_mech_` username prefix and all eight specializations. **While the fleet runs, simulated mechanics share the real database and can win real SOS requests.**

## Storage and safety notes

- Uploaded images are stored under `uploads/sos/`, `uploads/chat/`, and `uploads/shops/`; only filenames are stored in the database. Allowed types are JPEG, PNG, and WEBP with a 5 MB service-side limit (10 MB multipart ceiling).
- `EventRecorder` writes to `event_log` and `logs/request-{id}.jsonl`; completed or cancelled requests get a Java-serialized snapshot at `replays/request-{id}.ser`.
- `GET /api/requests/{id}/replay` is restricted to the request's driver, assigned mechanic, or an admin.
- Password reset codes are returned in the `codeHint` API field for demo convenience, and without SMTP configured the code is logged to the console instead of emailed. Do not expose this behavior in a public deployment.
- The admin accept-lock toggle intentionally allows multiple winners with the lock off; turn it back on after the demonstration.
- `docs/DOCUMENTATION.md` contains the full design rationale and demo script. Some test counts and table descriptions there predate the current implementation; the source is authoritative.

## License

No license has been specified in this repository.
