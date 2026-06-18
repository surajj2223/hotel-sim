# DESIGN — Folio completion lifecycle (`completeLine` + `completeFolio`)

> **Design note (not a frozen contract).** Records the completion-lifecycle decision taken at
> Stage 2 close-out. Pairs with `RX-003` (the balance split that makes the gate predicate
> honest). Becomes the basis for the gated Claude Code prompt. Frozen statements it touches
> are flagged via RX-003, not edited here.
>
> **Owner / arbiter:** Desk.
> **Depends on:** `RX-003` (customerOwes), INV-007 (`HumanAuthorizationGate`),
> ENM-002 (`BookingStatus`), ENM-003 (`BookingLineStatus`), existing `cancelLine` rollup.

---

## 1. The gap

`BookingStatus.COMPLETED` and `BookingLineStatus.COMPLETED` are declared (ENM-002, ENM-003)
but **no code path sets either**. The only live transitions are `PENDING → CONFIRMED` (first
line added) and `* → CANCELLED` (line cancel; booking flips to `CANCELLED` only when *all*
lines are cancelled). `COMPLETED` is a dead enum value on both entities — a terminal state with
no driver. There is no path from "service rendered and settled" to "done".

This blocks three downstream stages: `listUnpaidBookings` / revenue reads (Stage 6) need a
"finished + settled" state distinct from "open"; the cross-vertical folio (Stage 4) makes
line-vs-folio completion genuinely different events (a spa line done Wednesday, a room line done
Friday checkout); refunds (Stage 5) need the terminal state defined before the rule that guards
it. Closing it now, before Stage 3, is correct.

---

## 2. The two decisions

### Line completion is an explicit per-line act (not derived).

A line moves `ACTIVE → COMPLETED` via `completeLine(lineId)`. **Not** auto-derived from "service
window passed", because the completing trigger genuinely differs per vertical (spa = treatment
rendered, a point event; room = checkout, end of a multi-night window; F&B = bill settled at
table) and a human/agent is the right authority for "this happened". `completeLine` has **no
folio side effect** — completing a line never flips the booking. Only `CANCELLED` lines and
future-windowed lines are excluded from "completable"; an `ACTIVE` line whose service is done is
the normal case.

### Folio completion is an explicit, human-gated act (not a rollup).

A booking moves `CONFIRMED → COMPLETED` via `completeFolio(bookingId)`, a **repercussive write
gated by INV-007** (`X-Human-Auth`), exactly like `capturePayment`. It is **not** an emergent
rollup off the last line completing. Three reasons:

1. **Checkout is a real operational moment.** "Close out the folio" is a deliberate front-desk
   act with a human standing behind it — which is precisely what INV-007 exists to require. An
   emergent flip has no such moment.
2. **The MCP needs the verb.** "Close out Mrs. Chen's folio" → one intent → one gated write.
   A rollup-only model gives the agent no completion verb; it would complete lines one-by-one
   and hope the folio flips. That breaks the "one instruction, one write" orchestration story
   (charter §3).
3. **The guard is a folio-level invariant.** `customerOwes == 0 AND all-non-cancelled-lines-
   COMPLETED` is checked once, loudly, at the moment of intent — not re-evaluated inside every
   `completeLine`.

### The deliberate asymmetry with `cancelLine` (do not "tidy" it).

`cancelLine` rolls up emergently (`allMatch(CANCELLED) → booking CANCELLED`): destructive,
per-line-driven, no gate beyond the line cancel itself. `completeFolio` is an explicit gated
folio-level act: constructive, folio-driven. **This asymmetry is intentional.** A future reader
must not "simplify" completion into a symmetric rollup — that would lose the human moment (2)
and the MCP verb (3). This paragraph exists so the asymmetry survives.

---

## 3. The gate predicate (write-time revalidation)

`completeFolio(bookingId)` revalidates atomically and **fails loudly** (does not write) if
either condition is false:

- **C1 — all lines done:** every line with `status != CANCELLED` has `status == COMPLETED`.
  (A `CONFIRMED` folio with any still-`ACTIVE` line is not completable — finish the lines first.)
- **C2 — settled:** `customerOwes == 0` (per RX-003 D2; refund-driven non-zero `netRevenue` is
  irrelevant and correctly does not block).

On success: `booking.status = COMPLETED`. On failure: `409`/`422` with the current state
echoed (which condition failed, which lines are not yet COMPLETED, the live `customerOwes`),
consistent with the "fail loudly with current state" write-time-revalidation rule (charter §4).

A `CANCELLED` booking is terminal — `completeFolio` on it is a no-op error. A `COMPLETED`
booking is terminal — `completeFolio` is **idempotent-success** (`200`, returns the
already-completed folio; Q2 resolved), matching the webhook idempotency posture.

---

## 4. Surface (shape, not signatures)

- `completeLine(lineId)` — sets `ACTIVE → COMPLETED`. **Not** INV-007 gated (it posts nothing;
  mirrors `cancelLine`'s ungated posture — Q1 resolved, see §5).
- `completeFolio(bookingId)` — **INV-007 gated** (`X-Human-Auth`). Revalidates C1+C2.
  `CONFIRMED → COMPLETED`.
- Both are operational writes serving `ops-web` first (charter acceptance test); the MCP wraps
  them additively in Wave 2. No agent-only capability.

---

## 5. Resolved decisions

- **Q1 — `completeLine` is NOT gated.** It posts nothing to the ledger, moves no money, and
  consumes no inventory; the booking already consumed availability at commit. INV-007 tracks
  *repercussion*, not lifecycle membership — and `cancelLine` (more consequential, it can
  release inventory) is itself ungated, so gating `completeLine` would be the *inconsistent*
  choice. **Re-check trigger:** if `completeLine` ever grows a repercussive side effect (e.g.
  completing the last line auto-fires a final capture or releases a held deposit), it crosses
  the bar and must then be gated. Ungated *because it posts nothing*.
- **Q2 — `completeFolio` on an already-`COMPLETED` booking is idempotent-success** (`200`,
  returns the already-completed folio). Matches the webhook idempotency posture. A `CANCELLED`
  booking is still a terminal error (cannot complete a cancelled folio).
- **Q3 — Straggler `ACTIVE` line at folio-complete: HARD-FAIL C1, loudly.** No silent cascade.
  An `ACTIVE` line at checkout is ambiguous — either the operator forgot to tick it, or the
  service genuinely did not happen (no-show) and the line should be *cancelled*, not completed.
  Cascade cannot distinguish these and would fabricate a false "rendered" record, corrupting
  revenue-by-vertical (Stage 6). Hard-fail forces a deliberate human decision (complete vs
  cancel each line) at the moment the information is available. The error body names every
  straggler so `ops-web`/the agent can surface it ("spa treatment not marked done — did she
  take it?"). **Deliberate cascade belongs in the head, not the body:** `ops-web` may offer a
  "complete all remaining → check out" control that completes the lines then the folio via
  separate calls — a human choosing to cascade with a click, never the server doing it silently.

---

## 6. Definition of done

- `completeLine` + `completeFolio` service methods + controller endpoints.
- INV-007 gate on `completeFolio` (header wired exactly as `capturePayment`).
- Write-time revalidation C1+C2 with loud, current-state failure bodies.
- Tests: line ACTIVE→COMPLETED; folio happy path; C1 fail (straggler ACTIVE line); C2 fail
  (customerOwes != 0); refunded-but-settled folio completes (ties to RX-003 §5 test).
- `ops-web`: a "complete line" control and a "check out / complete folio" control, proving the
  charter acceptance test (same write from a button).
- `CHANGELOG.md` entry; §1b unaffected (no new frozen statement — this is additive behaviour
  over existing enums).
