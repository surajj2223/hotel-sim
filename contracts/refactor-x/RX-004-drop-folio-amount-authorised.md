# RX-004 — folio `amount_authorised` dropped; live hold derived on read

> **Refactor record (append-only).** This is not a new contract; it is a delta against the
> Stage-4 "D3" folio authorised roll-up (the `booking.amount_authorised` column added by
> `V3__payment_line_scoping.sql` and surfaced as `FolioResponse.amountAuthorised`). It records
> one definitional defect — a stored derivation that inflated the folio "secured" figure — and
> names the frozen statements it supersedes.
>
> **The Freeze Ledger (`WAVE0_00 §1b`) is the authoritative index.** A reader landing on the
> superseded statement (the WHK-016/Slice-S2 row's `amountAuthorised` mention, or the
> `FolioResponse.amountAuthorised` description) in isolation must consult §1b's `Superseded-by`
> column before building against it. The pointer banner on the superseded statement says *only*
> that — it does not restate the change. The "what changed" lives here.
>
> **Naming note.** The "D3" superseded here is the **Stage-4 Slice-1 folio authorised roll-up**
> (born in the WHK-016 / V3 work — see `WAVE0_00 §1b` changelog v0.8), **not** RX-001's D3, which
> is the unrelated outbound-PSP fail-loud / tx-ordering decision. The two are unrelated.
>
> **Self-rule (append-only).** RX-004 is itself frozen on commit. It is revised only by a
> superseding `RX-005`, never edited in place. Same discipline as the `WAVE0_0X` contracts and
> RX-001/002/003.
>
> **Status:** `FROZEN` on Desk sign-off 2026-06-30 (authoritative in `WAVE0_00 §1b`).
> **Owner / arbiter:** Desk.
> **Depends on:** `WAVE0_02_OPENAPI.yaml` (`FolioResponse.amountAuthorised`), the
> `V3__payment_line_scoping.sql` additive column, `WAVE0_00_OVERVIEW.md` §1b/§4.

---

## 1. Context

Stage 4 Slice 1 (WHK-016) added a booking-level "secured" figure, `booking.amount_authorised`,
maintained alongside `total/paid/refunded` in `BookingService.recalculateTotals` and surfaced
read-only as `FolioResponse.amountAuthorised` ("D3 — live folio authorised roll-up, visible
only, no enforcement").

### The defect

The stored roll-up summed **every** payment's `amount_authorised` for the booking, regardless
of payment status:

```
amount_authorised = Σ payment.amount_authorised   -- over ALL payments
```

An IMMEDIATE payment is authorised and then captured; its `amount_authorised` stays stamped on
the payment after capture (it is the per-payment ceiling the capture guard reads — SCH-032 /
`chk_pay_capture_le_auth`). The booking roll-up kept counting that **spent** auth as if it were
still a live hold. Walk a room (MANUAL, £510 held) + spa (IMMEDIATE, £290 captured) folio:

| Moment                                    | live hold (correct) | stored roll-up (frozen) |
|-------------------------------------------|---------------------|-------------------------|
| Room authorised, spa authorised           | 800                 | 800 ✓                   |
| Spa **captured**, room still held         | **510**             | **800** ✗               |
| Room **captured** too                     | **0**               | **800** ✗               |

After the spa captures, only the £510 room hold is genuinely live, but the stored figure reads
£800 and never falls. The number is monotonic-ish noise, not a hold.

### Why this is safe to change

The figure **gates nothing**. No capture guard, no completion precondition, and no posting
reads `booking.amount_authorised`:

- The capture guard (`PaymentService.assertCapturable`) bounds capture by the **per-payment**
  `payment.amount_authorised`, not the booking roll-up — partial capture (authorise £600,
  capture £540) is validated there and is untouched by this RX.
- `completeFolio`'s settlement gate keys on `customerOwes` (RX-003), not on this figure.
- The `booking_balance` view (SCH-021 / V6) reads `total/paid/refunded` only.

Its **only** producer was `recalculateTotals`; its **only** consumer was `DtoMapper` folio
assembly.

### Root cause

A *stored derivation* of a *live* quantity. Liveness ("which auths are still held right now?")
is a function of current payment status; persisting a snapshot of it lets the snapshot drift
the moment a status changes. The honest shape is to derive it on read.

Per `WAVE0_00 §4` and the append-only discipline, the delta is recorded here rather than by
editing the frozen `V3` migration or the frozen `FolioResponse.amountAuthorised` description in
place.

---

## 2. Decision

### D1 — Drop the stored `booking.amount_authorised` column; derive the figure on read.

The folio "secured" figure becomes a **live hold** computed at folio-assembly time:

```
amountAuthorised = Σ payment.amount_authorised   WHERE payment.status = 'AUTHORISED'
```

A captured (IMMEDIATE or MANUAL) payment's auth is spent and excluded; only auths still held
contribute. The figure is never stored on the booking. The response field
`FolioResponse.amountAuthorised` keeps its **shape**; only its computation and meaning change.

### D2 — The per-payment `amountAuthorised` is the source of truth and is NOT touched.

`Payment.amountAuthorised`, `PaymentResponse.amountAuthorised`, the V1 `payment.amount_authorised`
column, its `chk_pay_capture_le_auth` constraint, and `CaptureRequest`/the capture guard all
stand unchanged. This RX is strictly booking-grain.

### D3 — `recalculateTotals` no longer maintains an authorised roll-up; `recordAuthorisation`
drops its now-pointless recalc.

- `recalculateTotals` keeps `total/paid/refunded` (which gate `completeFolio` via `customerOwes`)
  and removes the `authorised` line only.
- `recordAuthorisation` (the WHK-006 AUTHORISATION handler) previously called `recalculateTotals`
  **solely** to refresh the authorised roll-up. With the roll-up gone, an AUTHORISATION changes
  none of `total/paid/refunded` (it captures nothing, refunds nothing, mutates no line), so the
  recalc is a no-op and is removed. `settleCapture` / `settleRefund` keep their recalc — they do
  change `paid` / `refunded`.

### D4 — `FolioResponse.amountAuthorised` is redefined (not removed) in the OpenAPI contract.

Description rewritten from "sum of `payment.amountAuthorised` for the booking" to the
status-gated live-hold definition. Field shape unchanged → backward-compatible for any reader
that only consumes the number; the *meaning* narrows. `ops-web` is the only consumer.

---

## 3. Superseded statements (for §1b)

| Frozen statement | File | Superseded-by | Pointer banner to add |
|------------------|------|---------------|-----------------------|
| `booking.amount_authorised` stored roll-up + `FolioResponse.amountAuthorised` "sum of all payments' auth" meaning (WHK-016 / API-008 lineCoverage Slice S2 row) | `WAVE0_00 §1b` | RX-004 §2 D1/D4 | "folio amount_authorised dropped; derived as live hold on read — see RX-004" |

The `V3__payment_line_scoping.sql` migration that added the column is **frozen history** and is
not edited; the column is removed by a forward `V7__drop_booking_amount_authorised.sql`. The V3
`payment_line` table (the WHK-016 substance) is untouched.

---

## 4. Blast radius (verified against repo at scoping time)

`booking.amount_authorised` / `Booking.getAmountAuthorised()` / folio-grain `amountAuthorised`
consumers, exhaustive:

- `Booking.java` — `amount_authorised` `@Column` field + Lombok getter/setter (removed).
- `V3__payment_line_scoping.sql` — added the column (frozen history; removed forward by V7).
- `BookingService.recalculateTotals` — the `authorised` line (removed; total/paid/refunded stay).
- `PaymentService.recordAuthorisation` — the recalc-for-authorised-only call (removed).
- `PaymentRepository.sumAuthorisedForBooking` — repurposed: now status-filtered to AUTHORISED.
- `DtoMapper.toFolio` — read `booking.getAmountAuthorised()` → `paymentRepository
  .sumAuthorisedForBooking(bookingId)` (the live-hold derivation).
- `FolioResponse.amountAuthorised` — record field kept; javadoc + OpenAPI description redefined.
- Tests: folio-grain assertions in `ScopedRevenueHttpApiTest` / `ScopedAllocationApiTest`
  (`assertFolio`) flip to live-hold; `ImmediateCaptureApiTest` gains an auth→capture folio
  regression. **Payment-grain** `amountAuthorised` assertions (`PaymentApiTest`,
  `ImmediateCaptureApiTest` payment rows, `WebhookApiTest`) are **not** touched (D2).

No payment/ledger posting logic touches the booking roll-up — ledger posts per-line on capture
(WHK-007/012), independent of this read-model number. The change is **read-model only**; no
posting, capture, or refund behaviour changes.

---

## 5. Definition of done

- `V7__drop_booking_amount_authorised.sql` drops `booking.amount_authorised` (forward; V3 untouched).
- `Booking.amountAuthorised` field + getter/setter removed.
- `recalculateTotals` authorised line removed; `total/paid/refunded` intact.
- `recordAuthorisation` recalc removed (D3, justified above).
- `sumAuthorisedForBooking` JPQL status-filtered to `AUTHORISED`; `DtoMapper.toFolio` derives the
  field from it; payment-grain mapping untouched.
- Capture guard (`assertCapturable`) verified reading per-payment auth — untouched.
- Regression: IMMEDIATE auth→capture leaves folio `amountAuthorised == 0` after settlement, with
  the mid-flight (auth-before-capture) value correctly == that payment's auth (not a bug);
  MANUAL still-held shows its auth as live; partial-capture and multi-payment-toward-one-folio
  stay green.
- `FolioResponse.amountAuthorised` redefined in `WAVE0_02_OPENAPI.yaml` (DRAFT).
- §1b updated with the superseded-statement pointer; banner added.
- `WAVE0_00 §7` changelog entry.
