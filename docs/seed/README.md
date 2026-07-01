# Demo data creator (`seed-data`)

Populates a running hotel-sim stack with presentable, correct-by-construction
data for `ops-web`: ~12 customers, ~34 folios spread across the last ~90 days
(many separate visits per customer plus a few deliberately cross-vertical
stays), and the full payment lifecycle underneath — clean stays, in-house
holds, partial payments, partial captures, refunds, cancelled auths, and
abandoned bookings.

It calls **only the frozen HTTP contracts** `ops-web` will consume. It never
writes to the database. Every posting, capture, and refund is produced by
`core-api`, so the data can only reach states the system actually permits — the
seed run doubles as an end-to-end exercise of the money loop.

## Prerequisites

1. **Stack up under the `test` profile** — the authorise trigger is
   `@Profile("test")`, so the plain compose file won't expose it:
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.smoke.yml up -d
   ```
2. **Products seeded** (once):
   ```bash
   docker compose exec -T db psql -U hotelops -d hotelops \
     < docs/seed/seed-products.sql
   # optional richer catalogue (safe to skip — script probes what exists):
   docker compose exec -T db psql -U hotelops -d hotelops \
     < docs/seed/seed-products-extra.sql
   ```
3. **Node 18+** (uses native `fetch`; no dependencies, no `npm install`).

## Run

```bash
node docs/seed/seed.mjs
```

Environment overrides (all optional):

| Var | Default | Meaning |
|-----|---------|---------|
| `CORE_URL` | `http://localhost:8080` | core-api base URL |
| `SIM_URL` | `http://localhost:8081` | payments-sim base URL |
| `HUMAN_AUTH` | `seed-script-operator` | value sent as `X-Human-Auth` on gated writes |
| `POLL_TIMEOUT_MS` | `15000` | max wait for an async ledger posting to land |
| `POLL_INTERVAL_MS` | `250` | poll interval while waiting |

The script prints a scenario summary, the revenue split by vertical, and the
unpaid worklist count, then the total HTTP call count — so a green run is
self-verifying.

## The one invariant that matters (why an earlier version failed)

**Revenue posts to the ledger asynchronously.** The capture webhook — even under
`?sync=true` — only *enqueues* a PENDING outbox event. A separate
`@Scheduled(fixedDelay=5000)` processor writes the actual `ledger_posting` rows
up to ~5 s later. **There is no synchronous capture in this system.**

`LedgerService.postCapture` allocates the captured amount across the folio's
**ACTIVE** booking lines. So if a line is completed (`ACTIVE → COMPLETED`)
*before* the outbox processor runs, the allocation finds no ACTIVE line, throws,
and the outbox row is marked `FAILED` — no posting is written and the
transaction rolls back. That produces the exact failure "lots of data but no
payment/ledger lines, and failed `outbox_event` rows."

The fix: after every revenue-posting payment, the script polls
`GET /bookings/{id}` until each line's derived `revenuePosted` is non-zero
(the authoritative "posting landed" signal), and only *then* completes the line
and folio. Folios that are meant to stay OPEN (in-house held, partial payment,
partial capture, full refund, cancelled auth, abandoned) never complete, so they
never race.

## Two more invariants (learned from a real run)

**Room `quantity` means rooms, not nights.** `RoomStrategy` computes availability as
`roomCount − committedOverlap ≥ quantity`, and the line total as
`basePrice × quantity × nights`, where **nights derive from the date window**
(`ChronoUnit.DAYS.between(startsAt, endsAt)`). Passing nights as `quantity`
double-counts: it requests N rooms (often exceeding inventory → 409) and bills
`rate × N rooms × N nights`. Every room line here uses `quantity = 1`; the stay
length lives entirely in `startsAt`/`endsAt`.

**Multi-line folios must use scoped payments (`lineCoverage`), and each scoped
payment must set its capture mode explicitly.** Two reasons, both verified:
1. A folio-wide payment allocates fill-by-line-order across *all* active lines. If a
   folio has two lines each paid by its own untargeted payment, the second payment's
   revenue lands on the *first* line and the second line never gets a posting — its
   `revenuePosted` stays 0 forever (an earlier version timed out here). Scoped
   `lineCoverage: [{bookingLineId, amount}]` posts to exactly the covered line.
2. Capture mode defaults from the folio's **first active line**, not the covered
   line. A dinner-scoped payment on a room-first folio would inherit the room's
   MANUAL mode and never auto-capture. So the script sets `captureMode` explicitly
   per line's vertical (ROOM→MANUAL, SPA/FNB→IMMEDIATE) on every scoped payment.

Both of these are also latent sharp edges for `ops-web`/MCP, flagged below.

## Other design choices

**Endpoints, not DB inserts.** All domain logic lives in `core-api`; seeding
through the API keeps a single source of truth. Direct inserts would fork that
logic and drift.

**90-day history from stay dates, not posting dates.** `startsAt`/`endsAt` are
request fields, so visits scatter across ~90 days for real operational history.
Ledger *posting* time is server-stamped "now", so a revenue **trend by calendar
date** is intentionally out of scope (see
`docs/system-design/seedable-posting-time.md`). The revenue **split by vertical**
and the **unpaid worklist** are fully real regardless.

**Correct pspReference chaining.** After a `?sync=true` authorise the webhook has
already landed, so the authoritative `pspReference` (and a refund's
`merchantReference`) is read back from `core-api`'s stamped payment state, never
from the sim's trigger response.

## Latent questions worth flagging (not the seeder's bugs)

These surfaced while building the seeder but are properties of the system, worth a
design note and arbitration — the script works around them, it doesn't fix them.

1. **Complete-before-post race.** `completeLine` is ungated; an operator could mark a
   line "done" seconds after payment, before the async outbox posts revenue. Then
   `postCapture` finds the line no longer ACTIVE and the outbox event fails. Should
   `postCapture` allocate across lines ACTIVE *at capture time* (or
   COMPLETED-but-unposted lines), or should completing a line before its revenue
   posts be rejected louder?

2. **Silent misallocation across multiple folio-wide payments.** If a folio has two
   lines and an operator pays each with a separate "pay folio balance" action (no
   `lineCoverage`), the second payment's revenue silently lands on the first line.
   Should a folio-wide payment be rejected when a prior payment already covered some
   lines, or should the UI always emit `lineCoverage`?

3. **Capture mode keys off the first line, not the paid line.** A scoped payment that
   settles only line 2 still inherits line 1's vertical capture-mode default unless an
   override is passed. Should the default derive from the covered line(s) when
   coverage is present?

## What it is / isn't

This is a documentation/tooling artifact that *consumes* frozen contracts. It
implements no contract and touches no service code, so it sits outside the
DRAFT→FROZEN discipline — no freeze-flip applies. If a run surfaces a mismatch
against a frozen contract, that's a flag for arbitration, not something the
script should paper over.