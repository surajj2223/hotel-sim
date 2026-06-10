# Wave 0 — Scaffold, Compose & Health Checks (`SCF-`)

> **What this freezes.** The repository layout, the local run mechanism (docker-compose),
> and the health-check seam that proves the skeleton is wired. This is the
> integration-owner artifact: every package reads it at setup. See
> `WAVE0_00_OVERVIEW.md` §1 for where it sits in the set and §5 for the Wave 0 gate.
>
> **Scope of this slice (pragmatic).** Only the services that exist today are scaffolded:
> **Postgres + `core-api`**. The skeleton is empty-but-wired — no controllers, DTOs, or
> business logic (that is Wave 1). `payments-sim`, `pay-web`, and `ops-web` — and the
> `WAVE0_03_WEBHOOK_PSP_CONTRACT.md` contract — are **deferred** (see §4).

---

## 1. Repository layout (as it actually is now)

```
hotel-sim/
├── docker-compose.yml              # SCF-003 — db + core-api, health-gated
├── contracts/
│   ├── WAVE0_00_OVERVIEW.md        # the set, freeze rule, change protocol, the gate
│   ├── WAVE0_01_SCHEMA.sql         # Postgres DDL (SCH-/ENM-/INV-)        [FROZEN]
│   ├── WAVE0_02_OPENAPI.yaml       # core-api HTTP contract, Stage 1 slice (API-)
│   ├── WAVE0_04_SCAFFOLD.md        # this file (SCF-)
│   └── project-brief.md
└── core-api/                       # the entire system body (Spring Boot 3.5, Java 17)
    ├── Dockerfile                  # SCF-002 — multi-stage: JDK17 build → JRE17 runtime
    ├── .dockerignore
    ├── build.gradle                # SCF-001 — actuator added here
    ├── settings.gradle
    ├── gradlew / gradle/wrapper/   # Gradle 8.14.5 wrapper (build uses this)
    └── src/
        ├── main/
        │   ├── java/com/hotelops/core/   # domain + persistence (Wave 1 Package A)
        │   └── resources/
        │       ├── application.yml       # SCF-001 — health exposure; localhost defaults
        │       └── db/migration/
        │           └── V1__wave0_schema.sql   # Flyway owns the schema
        └── test/                         # Testcontainers-backed entity/invariant tests
```

> **Not present yet (by design):** `payments-sim/`, `pay-web/`, `ops-web/`, and
> `WAVE0_03_WEBHOOK_PSP_CONTRACT.md`. Their directories are created by the stage that
> first needs them — adding empty stubs now would be scaffold-for-its-own-sake.

---

## 2. How to run it

Prerequisites: Docker + Docker Compose. From the repo root:

```bash
docker compose up --build
```

This will:

1. Start **`db`** (`postgres:16-alpine`, database/user/password all `hotelops`) on a named
   volume, and wait until `pg_isready` reports healthy.
2. Build **`core-api`** from `core-api/Dockerfile` and start it only **after** `db` is
   healthy (`depends_on: condition: service_healthy`).
3. `core-api` connects to the `db` service host via the `SPRING_DATASOURCE_URL`
   **environment** override; Flyway applies `V1__wave0_schema.sql` to the fresh database.

**Where health lives:**

| Service    | Health probe                                              | Exposed at |
|------------|-----------------------------------------------------------|------------|
| `db`       | `pg_isready -U hotelops -d hotelops`                       | internal   |
| `core-api` | `GET /actuator/health` → `{"status":"UP"}`                | `http://localhost:8080/actuator/health` |

Once both are healthy: `docker compose ps` shows both `(healthy)`. Tear down with
`docker compose down` (add `-v` to drop the database volume).

**Non-compose runs.** `application.yml` keeps `localhost:5432` datasource defaults, so
`core-api` runs against a locally reachable Postgres without any compose env overrides.
Compose overrides only the host, via environment — the file is never rewritten.

---

## 3. Configuration seams (so packages don't guess)

| Seam | Value | Set by |
|------|-------|--------|
| DB name / user / password | `hotelops` / `hotelops` / `hotelops` | compose `db` env + `application.yml` |
| DB port (host) | `5432` | `db` service |
| `core-api` port (host) | `8080` | `core-api` service `8080:8080` |
| Schema ownership | Flyway (`ddl-auto: none`) | `application.yml` |
| Migrations location | `classpath:db/migration` (`V1__wave0_schema.sql`) | `application.yml` |
| Datasource host override | `SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/hotelops` | compose env only |
| Health exposure | `management.endpoints.web.exposure.include: health` | `application.yml` |

---

## 4. Deferred services (explicitly out of scope this slice)

| Deferred item | Why deferred | Created when |
|---------------|--------------|--------------|
| `payments-sim` | No payment orchestration exists yet | Wave 1 Package E |
| `pay-web` | Front-end for the simulator | Wave 1 Package E |
| `ops-web` | Console built against the OpenAPI mock | Wave 1 Package F |
| `WAVE0_03_WEBHOOK_PSP_CONTRACT.md` | PSP event vocabulary not needed until payments | The stage that first needs it |

When a deferred service lands, this artifact gains its compose service, its health check,
and a new `SCF-` row — via the change protocol in `WAVE0_00_OVERVIEW.md` §4, not ad hoc.

---

## 5. Requirements (`SCF-`)

| ID | Requirement | Acceptance criteria | Depends-on |
|----|-------------|---------------------|------------|
| SCF-001 | `core-api` exposes a health endpoint. | `spring-boot-starter-actuator` present; `GET /actuator/health` returns `200 {"status":"UP"}`. | — |
| SCF-002 | `core-api` has a container image build. | Multi-stage `Dockerfile`: builds the bootJar with the Gradle wrapper on a JDK 17 base, runs it on a JRE 17 base, runs non-root, exposes 8080. | SCF-001 |
| SCF-003 | `docker-compose up` brings up db + core-api, health-gated. | `db` = `postgres:16-alpine` with named volume + `pg_isready` healthcheck; `core-api` built from its Dockerfile, `depends_on` db `service_healthy`, datasource set via environment, `8080:8080`, healthcheck on `/actuator/health`. | SCF-001, SCF-002 |
| SCF-004 | This scaffold artifact documents layout, run, deferrals, and `SCF-` IDs. | Layout matches the repo; run instructions reproduce green health checks; deferred services listed; requirements table present. | SCF-003 |

---

## 6. Accountability

- **Owner:** integration owner.
- **Status:** `DRAFT` → `FROZEN` on sign-off. (Wave 0 gate item, `WAVE0_00_OVERVIEW.md` §5.)
- **Sign-off:** _pending._

### Verification log

| ID | What was built | Commit | Proof |
|----|----------------|--------|-------|
| SCF-001 | actuator added to `build.gradle`; health exposure in `application.yml`. | `feat(core-api): add actuator and expose health endpoint [SCF-001]` | App started against Postgres 16; `GET /actuator/health` → `200 {"status":"UP"}`; Flyway applied `V1` (`flyway_schema_history.success = t`). |
| SCF-002 | Multi-stage `Dockerfile` (JDK17 build → JRE17 runtime, non-root). | `feat(core-api): add multi-stage Dockerfile (JDK17 build, JRE17 runtime) [SCF-002]` | Inner build command `./gradlew bootJar -x test` verified directly: produced `build/libs/core-api-0.0.1-SNAPSHOT.jar` (the artifact the runtime stage copies). |
| SCF-003 | `docker-compose.yml`: db + core-api, health-gated, datasource via env. | `feat(repo): add docker-compose for db + core-api with health checks [SCF-003]` | `docker compose config` validates; env override proven — running the jar with `SPRING_DATASOURCE_URL` host `db` yields `UnknownHostException: db`, confirming env wins over the yml localhost default. |
| SCF-004 | This artifact. | `feat(contracts): add Wave 0 scaffold artifact [SCF-004]` | Layout cross-checked against the working tree at write time. |

---

## 7. Changelog

| Version | Date | Change |
|---------|------|--------|
| 0.1 | FROZEN   | Initial scaffold slice — Postgres + core-api only; deferred services noted. |
