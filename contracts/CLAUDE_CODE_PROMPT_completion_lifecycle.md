# Claude Code prompt — Slice 2: folio completion lifecycle (`completeLine` + `completeFolio`)

**Plan-then-wait.** Produce a plan, list files, STOP at the gate. No code until Desk says go.

## Preconditions (verify, do not assume)
- **Slice 1 (RX-003) is merged.** `Booking.getCustomerOwes()` exists; `getBalance()` is gone.
  If `getCustomerOwes()` is absent, STOP — this slice depends on it for gate condition C2.
- `DESIGN_FOLIO_COMPLETION.md` is the design source. It is a design note, not a frozen
  contract; the behaviour here is **additive over existing FROZEN enums** ENM-002
  (`BookingStatus.COMPLETED`) and ENM-003 (`BookingLineStatus.COMPLETED`) — both already
  declared, neither currently driven. INV-007 (`HumanAuthorizationGate`) gates `completeFolio`.
  No new frozen contract statement is required (confirm this framing in your plan; if you think
  a contract ID is needed, flag it rather than inventing one).

## Scope (exactly this)
Two operational lifecycle writes, mirroring the existing `cancelLine` structure in
`BookingService`:

- **`completeLine(UUID lineId)`** — `ACTIVE → COMPLETED`. **Ungated** (posts nothing; mirrors
  the existing ungated `cancelLine` service method). No folio side effect — completing a line
  never flips the booking. Reject if the line is `CANCELLED` (terminal) — fail loudly.
- **`completeFolio(UUID bookingId)`** — `CONFIRMED → COMPLETED`. **INV-007 gated**
  (`X-Human-Auth`, wired exactly like `capturePayment` / `cancelAuthorisation` in the
  controllers). Revalidates atomically and fails loudly if either fails:
  - **C1** — every line with `status != CANCELLED` has `status == COMPLETED`. A straggler
    `ACTIVE` line → **hard-fail** (no cascade; Q3). Error body names every non-complete line.
  - **C2** — `booking.getCustomerOwes() == 0` (RX-003). Refund-driven `netRevenue != total` is
    irrelevant and must NOT block.
  - Already-`COMPLETED` booking → **idempotent success** (`200`, return the folio; Q2).
  - `CANCELLED` booking → terminal error (cannot complete).

## Files (target ≤6; if past 6, stop and flag a split)
1. `BookingService.java` — add `completeLine` and `completeFolio` next to `cancelLine`, mirroring
   its shape (find line, set status, save; for folio: revalidate C1+C2, set status). Reuse
   `recalculateTotals` if needed to ensure `customerOwes` is fresh before the C2 check.
2. Controller (the booking-lines controller; locate the one owning `cancelBookingLine`) — two
   endpoints. `completeFolio` takes the `X-Human-Auth` header and calls
   `humanAuth.assertAuthorised(token, "completeFolio")` BEFORE the service call. `completeLine`
   takes no auth header.
3. A new exception (or reuse an existing 409/422 family — check `GlobalExceptionHandler` and
   the `common/error` package first) for the C1/C2 hard-fail, carrying the current state
   (which condition, which lines, live `customerOwes`).
4. `GlobalExceptionHandler.java` — map that exception to the chosen status (recommend `409
   Conflict` for state-precondition failures; match whatever `cancelAuthorisation` on a bad
   state already does — check first).
5. DTO(s) for the failure body if one doesn't already fit (name the straggler line IDs +
   `customerOwes`).
6. `ops-web` — a "mark line done" control (calls `completeLine`) and a "check out / complete
   folio" control (calls `completeFolio` with the human-auth header). The deliberate "complete
   all remaining then check out" convenience (multiple calls, human-initiated) lives here, NOT
   in the server (Q3 — cascade is a head concern).

## Traps (call out in the plan)
- **T-A — folio side effect on `completeLine`.** completeLine must NOT roll up to the booking.
  Folio completion is explicit only. (Asymmetry with cancel is deliberate — DESIGN §2.)
- **T-B — gating `completeLine`.** It posts nothing; the existing `cancelLine` service method
  is ungated and is *more* consequential. Gating completeLine is the inconsistent choice.
- **T-C — silent cascade in `completeFolio`.** A straggler ACTIVE line is HARD-FAIL, not a
  quiet sweep. Cascade fabricates a "rendered" record for un-rendered service (Q3).
- **T-D — `@Transactional` self-invocation.** If `completeFolio` calls another `@Transactional`
  method on `this`, the proxy is bypassed (known trap). Keep the revalidation inline or in a
  separate bean.
- **T-E — C2 using the wrong number.** The gate is `customerOwes == 0`, NOT `netRevenue == 0`
  and NOT the old `balance`. A refunded-but-settled folio (`customerOwes == 0`,
  `netRevenue == 500`) MUST complete.
- **T-F — non-idempotent re-complete.** Second `completeFolio` on a COMPLETED booking returns
  `200` + folio, not `409` (Q2). But `CANCELLED` → error.
- **T-G — revalidation not atomic / stale totals.** Recompute `customerOwes` inside the same
  transaction as the status flip; do not trust a stale read (write-time revalidation, charter §4).

## Definition of done (each a test)
- `completeLine`: ACTIVE → COMPLETED; CANCELLED line → rejected.
- `completeFolio` happy path: all lines COMPLETED + `customerOwes == 0` → booking COMPLETED.
- C1 fail: one ACTIVE straggler → hard-fail, body names the line, booking stays CONFIRMED.
- C2 fail: `customerOwes != 0` → hard-fail, booking stays CONFIRMED.
- Refunded-but-settled: pay 600, refund 100, all lines COMPLETED → completes (ties RX-003 §5).
- Idempotent: second completeFolio on COMPLETED → 200; on CANCELLED → error.
- INV-007: completeFolio without `X-Human-Auth` → 428 (matches existing gated writes).
- Commit cites `[ENM-002, ENM-003, INV-007]` (+ `RX-003` for the C2 dependency).
- `CHANGELOG.md` entry.

## Out of scope
- Per-vertical completion *triggers* (auto-complete on window-pass) — explicitly rejected;
  completion is a human/agent act (DESIGN §2).
- Server-side cascade — head concern only.
- Any payment precondition on `completeLine` — settlement lives in C2 on the folio, not the line.
