# Running hotel-sim locally

A practical guide to running the project on your machine. This reflects what the
repo **actually contains today**, not the end-state described in the README.

> **What exists right now:** `core-api` (Spring Boot) + `db` (Postgres 16).
> `payments-sim`, `pay-web`, and `ops-web` are deferred — they aren't in the tree
> yet, and `docker-compose.yml` only defines the two services above.

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

- **Flyway owns the schema** (`ddl-auto: none`). The app **cannot boot without a
  reachable Postgres** — there's no embedded fallback. Always start the DB first.
- **The migration is schema-only — there is no seed data.** A fresh database has
  no products, so `/availability` returns an empty list and you can't add a booking
  line until you insert a room. See [Seeding a room](#seeding-a-room-needed-to-test-products).
- **DB credentials** (same everywhere): database `hotelops`, user `hotelops`,
  password `hotelops`, on port `5432`.
- **The app's default datasource** (`application.yml`) is
  `jdbc:postgresql://localhost:5432/hotelops`. Compose overrides the host to `db`
  via environment variables, so the same build runs both in Docker and on your host
  with no code change.

---

## Three ways to run

Pick the one that matches what you're doing. Most day-to-day work is **Option B**.

### Option A — All in Docker (clean-room / smoke check)

Builds `core-api` into an image and runs both services. Needs **only Docker** — no
JDK on your host. Slowest first run (Gradle resolves dependencies inside the image).

```bash
docker compose up --build -d      # build + start db + core-api
docker compose ps                 # wait until both are "healthy"
curl http://localhost:8080/actuator/health   # -> {"status":"UP"}
docker compose logs -f core-api   # tail logs
docker compose down               # stop (keeps the database volume)
```

Use this to verify the project runs from a clean checkout. Don't run it at the same
time as Option B — both bind host port `8080`.

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

> **Publishing the DB port is required for this option.** Container-internal Postgres
> isn't reachable from a host-run app. The `db` service in `docker-compose.yml` must
> publish the port:
> ```yaml
> db:
>   ports:
>     - "5432:5432"
> ```
> After this, `docker compose ps` shows `0.0.0.0:5432->5432/tcp`. Without the mapping
> you get `Connection to localhost:5432 refused` at startup.
>
> Tip: if you'd rather keep `docker-compose.yml` pristine as a frozen Wave 0 artifact,
> put the `db` `ports:` block in a `docker-compose.override.yml` instead — compose
> merges it automatically and it stays a local-only concern.

### Option C — DB in Docker, app via Gradle wrapper (no IDE)

Same topology as B, driven from a terminal. Needs JDK 21 on host.

```bash
docker compose up -d db
cd core-api
./gradlew bootRun
```

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

## Testing the endpoints (Stage 1)

Seven endpoints are live. All paths are relative to `http://localhost:8080`.

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/customers` | POST | Create a customer (server mints `shopperReference`) |
| `/customers/{id}` | GET | Fetch one customer |
| `/customers/{id}/preferences/{key}` | PUT | Upsert one preference value |
| `/availability` | GET | Room availability + price (**ROOM only**; other verticals return 400 by design) |
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
- **400 on non-ROOM availability** — `?vertical=SPA` returns 400. Stage 1 supports
  ROOM only — this is intentional, not a bug, and shows the `ApiError` envelope shape.

---

## Running the tests

The suite uses Testcontainers, which starts its **own throwaway Postgres** — it does
not use your dev database. Needs JDK 21 + a running Docker daemon.

```bash
cd core-api
./gradlew test     # report: build/reports/tests/test/index.html
```

Or run any test class from the IntelliJ gutter. Don't point these at your dev DB.

---

## Common issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Connection to localhost:5432 refused` on startup | DB not running, or port not published | `docker compose up -d db`; ensure the `db` `ports: "5432:5432"` mapping exists (`docker compose ps` shows `0.0.0.0:5432->`) |
| `docker compose ps` shows `5432/tcp` (no `0.0.0.0:`) | Port exposed internally but not published to host | Add the `ports` mapping to `db` (see Option B) |
| Port `8080` already allocated | Both Option A and an IDE run are up | Run only one at a time — `docker compose down`, or stop the IDE app |
| Port `5432` already allocated | A second Postgres (host-installed or another container) owns it | One Postgres on 5432 at a time; `lsof -iTCP:5432 -sTCP:LISTEN` finds the culprit |
| `/availability` returns `[]` | No products seeded | Run the [seed step](#seeding-a-room-needed-to-test-products) |
| Entity getters/setters won't resolve in IDE | Lombok annotation processing off | Enable annotation processing + Lombok plugin (Option B, step 4) |
| Compile errors about Java version | Project SDK isn't 21 | Set Project SDK to 21 (Option B, step 3) |

---

## Quick reference

```bash
docker compose up -d db            # start just the database (for IDE / gradle runs)
docker compose up --build -d       # full stack in Docker
docker compose ps                  # status — look for "healthy" and 0.0.0.0:5432->
docker compose logs -f core-api    # tail app logs (Docker run)
docker compose down                # stop, keep data
docker compose down -v             # stop, WIPE the database volume (re-seed after)
curl localhost:8080/actuator/health
```
