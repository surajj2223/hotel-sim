# Running hotel-sim locally

A practical guide to running the project on your machine. This reflects what the
repo **actually contains today**, not the end-state described in the README.

> **What exists right now:** two Spring Boot services — `core-api` (:8080) and
> `payments-sim` (:8081) — each with its own Postgres 16 (`db` on :5432,
> `payments-sim-db` on :5433). `docker-compose.yml` defines all four.
> `ops-web` and the MCP server are not in the tree yet.
>
> **The money loop is wired end-to-end (Stages 1–3, tag `stage-3.1`).** Three verticals
> (Rooms, Spa, F&B) are live, the folio-completion lifecycle and the charter §9 reporting
> reads are built, and `payments-sim` is fully built (mints
> references, persists state, fires signed webhooks), and the *outbound*
> `core-api → payments-sim` seam is now live: creating a link / capturing / cancelling /
> refunding drives a real PSP round-trip (PSP-001..017, `WAVE0_05 §9a`). The authorisation
> that a hosted checkout would otherwise trigger is driven by `payments-sim`'s test seam in
> the smoke harness (see [The money loop](#the-money-loop-smoke-harness)) — the base stack
> deliberately leaves that seam unreachable. This guide covers both the `core-api` inner
> loop and the full money loop.

---

## Prerequisites

| Tool | Needed for | Notes |
|------|-----------|-------|
| Docker Desktop | Everything (the database always runs in Docker) | Must be running before any command below |
| JDK 21 | Running `core-api` on your host (IDE or Gradle) | Not needed for the all-in-Docker path |
| IntelliJ IDEA **CE** | The IDE workflow | CE has no Spring plugin — Boot apps run as plain Java apps (covered below) |

The Gradle wrapper pins Gradle **8.14.5** and the build targets **Java 21** — you
don't install Gradle yourself; the wrapper handles it.

---

## Key facts that explain everything else

- **Flyway owns the schema** (`ddl-auto: none`) in **both** services. Neither app
  can boot without its Postgres reachable — there's no embedded fallback. Always
  start the DB(s) first.
- **Migrations are schema-only — there is no seed data.** A fresh `core-api` database
  has no products, so `/availability` returns an empty list and you can't add a
  booking line until you insert a room. See [Seeding a room](#seeding-a-room-needed-to-test-products).
  `payments-sim` needs no seed — it mints its own rows as payments happen.
- **Two databases, two credential sets** (kept deliberately separate per RX-001 —
  the PSP owns its own state):

  | Service DB | Database | User | Password | Host port |
  |---|---|---|---|---|
  | `db` (core-api) | `hotelops` | `hotelops` | `hotelops` | `5432` |
  | `payments-sim-db` | `pspsim` | `pspsim` | `pspsim` | `5433` |

- **Default datasources** (each service's `application.yml`): core-api →
  `jdbc:postgresql://localhost:5432/hotelops`; payments-sim →
  `jdbc:postgresql://localhost:5433/pspsim`. Compose overrides the host (to the
  service name) and the PSP wiring via environment variables, so the same build runs
  both in Docker and on your host with no code change.

---

## Ways to run

Pick the one that matches what you're doing. Most day-to-day work is **Option B**.
(Running `payments-sim` on the host is an optional add-on, covered after Option C.)

### Option A — All in Docker (clean-room / smoke check)

Builds both Spring Boot images and runs the full stack — `db`, `payments-sim-db`,
`core-api`, `payments-sim`. Needs **only Docker** — no JDK on your host. Slowest first
run (Gradle resolves dependencies inside each image).

```bash
docker compose up --build -d      # build + start all four services
docker compose ps                 # wait until all show "healthy"
curl http://localhost:8080/actuator/health   # core-api     -> {"status":"UP"}
curl http://localhost:8081/actuator/health   # payments-sim -> {"status":"UP"}
docker compose logs -f core-api   # tail logs (or: payments-sim)
docker compose down               # stop (keeps the database volumes)
```

Use this to verify the project runs from a clean checkout. Don't run it at the same
time as Option B/C — they bind the same host ports (`8080`, `5432`).

### Option B — DB in Docker, app in IntelliJ (recommended)

Fast inner loop: edit Java, rerun from the IDE, debugger attached.

1. **Start only Postgres:**
   ```bash
   docker compose up -d db
   docker compose ps        # db should show "healthy"
   ```
2. **Open the project:** open the **`core-api`** folder (the one with `build.gradle`),
   not the repo root. Let IntelliJ import the Gradle project.
3. **Set the SDK:** File → Project Structure → Project SDK → **21**
   (use "Download JDK" → Temurin 21 if you don't have one).
4. **Enable annotation processing** (the build uses Lombok):
   Settings → Build, Execution, Deployment → Compiler → Annotation Processors →
   **Enable annotation processing**. Confirm the Lombok plugin is enabled under
   Settings → Plugins. (Skip this and entity getters/setters won't resolve.)
5. **Run:** open `CoreApiApplication.java`, click the green ▶ in the gutter → Run.
   It boots on `localhost:8080`, Flyway migrates, health goes UP.

> **The DB ports are already published** in `docker-compose.yml` (`db` → `5432:5432`,
> `payments-sim-db` → `5433:5432`), so a host-run app reaches them out of the box —
> `docker compose ps` shows `0.0.0.0:5432->5432/tcp`. (If you ever see
> `Connection to localhost:5432 refused` at startup, the database simply isn't up yet;
> run `docker compose up -d db` and wait for `healthy`.)

### Option C — DB in Docker, app via Gradle wrapper (no IDE)

Same topology as B, driven from a terminal. Needs JDK 21 on host.

```bash
docker compose up -d db
cd core-api
./gradlew bootRun
```

### Running `payments-sim` on the host (optional)

Most core-api work doesn't need a host-run PSP. If you do want it (e.g. to watch it
mint references), start its database and run it the same way:

```bash
docker compose up -d payments-sim-db
cd payments-sim
./gradlew bootRun        # boots on :8081, Flyway migrates pspsim, health goes UP
```

> Its `application.yml` defaults the webhook callback and hosted-base URLs to the
> **compose** hostnames (`http://core-api:8080/...`, `http://payments-sim:8081`),
> which don't resolve on your host. To have a host-run `payments-sim` call back to a
> host-run `core-api`, override them:
> ```bash
> CORE_API_WEBHOOK_URL=http://localhost:8080/webhooks/psp \
> PSP_SIM_HOSTED_BASE_URL=http://localhost:8081 \
> ./gradlew bootRun
> ```
> The shared `PSP_WEBHOOK_SECRET` must match on both sides for signature
> verification to pass (compose sets both to `poc-psp-secret`).

---

## Seeding a room (needed to test products)

The schema ships empty. To exercise availability and bookings, insert one room.
Run against the Dockerized DB from the repo root:

```bash
docker compose exec db psql -U hotelops -d hotelops -c "
INSERT INTO product (id, vertical, name, base_price, currency)
VALUES ('11111111-1111-1111-1111-111111111111','ROOM','Deluxe King',18000,'GBP');
INSERT INTO product_room (product_id, floor_band, bed_type, max_occupancy, quiet, room_count)
VALUES ('11111111-1111-1111-1111-111111111111','high','KING',2,true,5);
"
```

This is a £180.00/night room (money is stored as BIGINT minor units), with a fixed
UUID for easy reuse and `room_count = 5` so there's availability to consume and
overbook against.

> Re-seed after any `down -v` / volume reset, since that wipes the data.

---

## Testing the endpoints

`core-api` exposes the booking-lifecycle endpoints below plus a set of payment/webhook
endpoints (listed after the happy path). All paths are relative to
`http://localhost:8080`.

### Booking lifecycle (no money)

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/customers` | POST | Create a customer (server mints `shopperReference`) |
| `/customers/{id}` | GET | Fetch one customer |
| `/customers/{id}/preferences/{key}` | PUT | Upsert one preference value |
| `/availability` | GET | Availability + price; resolves any registered vertical (ROOM, SPA, and FNB wired — SPA returns `spaAttributes`, FNB returns `fnbAttributes`; EVENT returns 400 until registered) |
| `/bookings` | POST | Open an empty folio for a customer |
| `/bookings/{id}/lines` | POST | Add a room line (revalidates availability + price, recomputes totals) |
| `/bookings/{id}` | GET | Read the folio with lines and derived amounts |

### Happy path (curl)

```bash
# 1. Availability — shows the seeded room once it exists
curl "http://localhost:8080/availability?vertical=ROOM&startsAt=2026-06-20T15:00:00Z&endsAt=2026-06-23T11:00:00Z"

# 2. Create a customer — note the returned id
curl -s -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -d '{"fullName":"John Patel","email":"jp@example.com","phone":"+44123456789"}'

# 3. Set a preference
curl -X PUT http://localhost:8080/customers/<CUSTOMER_ID>/preferences/floor \
  -H 'Content-Type: application/json' -d '{"value":"high"}'

# 4. Open a folio — note the returned booking id
curl -s -X POST http://localhost:8080/bookings \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"<CUSTOMER_ID>","currency":"GBP"}'

# 5. Add a room line
curl -X POST http://localhost:8080/bookings/<BOOKING_ID>/lines \
  -H 'Content-Type: application/json' \
  -d '{"productId":"11111111-1111-1111-1111-111111111111","startsAt":"2026-06-20T15:00:00Z","endsAt":"2026-06-23T11:00:00Z","quantity":1}'

# 6. Read the folio — totals reflect the line
curl http://localhost:8080/bookings/<BOOKING_ID>
```

> IntelliJ's built-in **HTTP Client** (a `.http` scratch file with clickable ▶ icons)
> works well here and captures responses — handy alternative to curl while in the IDE.

### The two behaviors worth testing on purpose

The interesting design lives in the edge cases, not the happy path:

- **409 on overbooking** — the seeded room has `room_count = 5`. Add overlapping
  lines whose quantity exceeds 5 in one window; the over-the-limit write fails loudly
  with a 409 rather than overbooking. This is the write-time revalidation (INV-003),
  the core safety mechanism.
- **SPA / FNB availability** — `?vertical=SPA` returns results with a `spaAttributes` object
  (treatmentKind, durationMinutes, therapistGender, concurrentSlots — Slice A3/A4), and
  `?vertical=FNB` returns results with an `fnbAttributes` object (servicePeriod,
  seatingMinutes, coversCapacity — Slice A5). `/availability` resolves any registered vertical
  via `VerticalStrategyRegistry`. `?vertical=EVENT` still returns `400` until its strategy
  registers — that path shows the `ApiError` envelope shape.

### Payment / webhook endpoints (Stage 2)

These are live on `core-api` and wired end-to-end: the *outbound* call from `core-api` to
`payments-sim` drives a real PSP round-trip (PSP-001..017, `WAVE0_05 §9a`). Capture / cancel
/ refund are async — they return `202` and the signed inbound webhook is the authoritative
completion signal. Paths/verbs below are from `WAVE0_02_OPENAPI.yaml`.

| Endpoint | Method | Purpose | Notes |
|----------|--------|---------|-------|
| `/bookings/{id}/payments` | POST | Create a payment link for a booking | Repercussive — requires `X-Human-Auth`; accepts optional scoped `lineCoverage` (folio-wide if omitted — WHK-012/016, API-008 Slice S2) |
| `/bookings/{id}/payments` | GET | List a booking's payments | |
| `/payments/{id}` | GET | Read one payment | |
| `/payments/{id}/capture` | POST | Capture (full/partial) | Async — returns `202`; `X-Human-Auth` |
| `/payments/{id}/cancel` | POST | Cancel an uncaptured authorisation | Async — returns `202`; `X-Human-Auth` |
| `/payments/{id}/refunds` | POST | Refund (full/partial) | Async — returns `202`; `X-Human-Auth` |
| `/webhooks/psp` | POST | Inbound PSP events (authoritative state signal) | `X-PSP-Signature` (HMAC), not human-gated |

> The folio read (`GET /bookings/{id}`) exposes a derived read-only `revenuePosted` per
> booking line (API-008 Slice S2) — the captured revenue posted to the ledger for that line,
> letting you confirm scoped allocation landed on the right vertical.

### Folio completion (Stage 3)

| Endpoint | Method | Purpose | Notes |
|----------|--------|---------|-------|
| `/bookings/{id}/lines/{lineId}/complete` | POST | Mark a line COMPLETED (ACTIVE→COMPLETED) | Ungated; posts nothing, moves no money (API-014) |
| `/bookings/{id}/complete` | POST | Complete a folio (CONFIRMED→COMPLETED) | Human-gated (`X-Human-Auth`, 428 if absent); write-time revalidated — 409 `FolioNotCompletable` unless all non-cancelled lines are COMPLETED and `customerOwes == 0`; idempotent 200 (API-015) |

### Reports (reads — Stage 3.1)

| Endpoint | Method | Purpose | Notes |
|----------|--------|---------|-------|
| `/reports/revenue` | GET | Revenue grouped by vertical | `?from&to` (both required); half-open `[from, to)` posting-time window; gross / refundedTotal / net per vertical + totals (API-016) |
| `/reports/unpaid-bookings` | GET | Bookings still owed (`total > paid`) | No params; per-line `lineOwes` (captured-only) and informational `lineHeldAuth` (API-017) |

Two auth behaviours worth seeing on purpose:

- **428 without `X-Human-Auth`** — a repercussive write (e.g. capture) with no
  `X-Human-Auth` header is rejected with `428 Precondition Required`. The header is
  presence-only in the POC (any value passes), but its *absence* is the server-side
  commit gate firing.
- **401 on a bad webhook signature** — posting to `/webhooks/psp` without a valid
  `X-PSP-Signature` returns `401`. The signature is an HMAC over the body using the
  shared `PSP_WEBHOOK_SECRET`.

### The money loop (smoke harness)

The full authorise → capture → ledger-posting loop across both services is driven by
the smoke override, which activates `payments-sim`'s `test` profile so its synchronous
test-trigger router is reachable (it's deliberately **404 in the normal stack**):

```bash
docker compose -f docker-compose.yml -f docker-compose.smoke.yml up -d
```

This is the integration owner's deterministic harness — it lets the end-to-end money
loop run without sleeps. It is not how the system behaves in a normal run, and the
test seam is unreachable outside this override by design.

---

## Running the tests

Both services have suites that use Testcontainers, which starts its **own throwaway
Postgres** — they don't touch your dev databases. Needs JDK 21 + a running Docker daemon.

```bash
cd core-api      && ./gradlew test     # report: build/reports/tests/test/index.html
cd payments-sim  && ./gradlew test     # the PSP's own suite
```

Or run any test class from the IntelliJ gutter. Don't point these at your dev DBs.

---

## Common issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Connection to localhost:5432 refused` on startup | core-api's DB isn't up yet | `docker compose up -d db`; wait for `healthy` (the `5432:5432` mapping already exists) |
| `Connection to localhost:5433 refused` (payments-sim) | the PSP's DB isn't up yet | `docker compose up -d payments-sim-db`; wait for `healthy` |
| Port `8080` / `8081` already allocated | A Docker run and a host run are both up | Run only one at a time — `docker compose down`, or stop the IDE/Gradle app |
| Port `5432` / `5433` already allocated | A second Postgres (host-installed or another container) owns it | One Postgres per port; `lsof -iTCP:5432 -sTCP:LISTEN` finds the culprit |
| `/availability` returns `[]` | No products seeded | Run the [seed step](#seeding-a-room-needed-to-test-products) |
| `428 Precondition Required` on a payment write | Missing `X-Human-Auth` header | Add the header (any value) — it's the commit gate, presence-only in the POC |
| `401` posting to `/webhooks/psp` | Missing/invalid `X-PSP-Signature`, or mismatched secret | Sign with the shared `PSP_WEBHOOK_SECRET` (compose uses `poc-psp-secret`) |
| Entity getters/setters won't resolve in IDE | Lombok annotation processing off | Enable annotation processing + Lombok plugin (Option B, step 4) |
| Compile errors about Java version | Project SDK isn't 21 | Set Project SDK to 21 (Option B, step 3) |

---

## Quick reference

```bash
docker compose up -d db payments-sim-db   # start just the databases (for IDE / gradle runs)
docker compose up --build -d              # full stack in Docker (all four services)
docker compose ps                         # status — look for "healthy" and 0.0.0.0:5432->, :5433->
docker compose logs -f core-api           # tail app logs (or: payments-sim)
docker compose down                       # stop, keep data
docker compose down -v                    # stop, WIPE both database volumes (re-seed the room after)
docker compose -f docker-compose.yml -f docker-compose.smoke.yml up -d   # money-loop smoke harness
curl localhost:8080/actuator/health       # core-api
curl localhost:8081/actuator/health       # payments-sim
```