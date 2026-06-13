# Out of Scope — Deliberate Deferrals & Known Limitations

> **Canonical home for "we are deliberately not doing this."** Each entry is a *flag,
> not a fix*: a conscious boundary recorded so it is visible, not a bug discovered later.
> PRD "Out of scope" sections and the README point here rather than duplicating.
>
> **Flag-don't-fix applies throughout.** A builder who finds a stage reaching past one of
> these boundaries must **stop and record it in the Contract-Drift Log** (HS-02), not
> silently implement it.

---

## How to read this file

| Field | Meaning |
|-------|---------|
| **Boundary** | The line we are deliberately not crossing in the POC. |
| **Current model** | What the system *does* do instead (the correct POC abstraction). |
| **Why deferred** | Why crossing the line is out of scope — usually "different problem, not a shortcut." |
| **If revisited** | The additive, non-drifting way to introduce it later. |
| **Guardrail** | The concrete signal that tells a builder they are about to cross it. |

---

## 1. Named-therapist resource scheduling (Spa)

**Boundary.** The Spa vertical does **not** model individual named therapists, does not
assign a specific therapist to a booking line, and does not prevent the same therapist
being double-booked across two treatments in the same window.

**Current model (capacity-as-integer).** Spa availability is `concurrent_slots` parallel
bookings per treatment window (SCH-012), consumed via the **shared** overlap query
`countCommittedQuantity` — identical in shape to Rooms' `room_count` minus committed
overlap. `therapist_gender` is a **preference target** the MCP/operator matches on
(facts from the system, judgement from the model — HS-01 proof point #1), **not** a hard
inventory constraint.

**Why deferred (the correct POC abstraction, not a shortcut).** Named-therapist
scheduling is a cross-product **resource-allocation** problem, not a per-product capacity
count. Introducing it would require: (a) a new `therapist` table + a
therapist↔booking_line association — a **frozen-DDL change**; (b) a second overlap query
keyed on `therapist_id` *across* products, which cannot be shared with Rooms and trips the
"no third overlap query" guardrail; and (c) a therapist-assignment branch in the booking
write path. That is a different POC (resource scheduling), not a test of the strategy
seam. Stage 3's purpose is to prove the strategy pattern generalises by *reusing* the seam
with **no schema change**; putting the hardest availability model in the first added
vertical would discard exactly that signal.

**If revisited.** Introduce only **after** the capacity-as-integer seam is proven across
all four verticals (N=4), as a deliberate vertical-spanning extension of a *proven* seam:
`therapist` as a new consumable resource (additive), never a silent amendment to a frozen
contract.

**Guardrail.** If Stage 3 (or any Spa work) reaches for a `therapist` table or a
per-therapist overlap query — **stop**. Record it in the HS-02 Contract-Drift Log.

---

## 2. Overbooking — non-locking availability check (TOCTOU)

**Boundary.** The availability check is a non-locking **read-decide-insert**: it reads
committed overlap, decides there is capacity, then inserts the booking line — with no lock
held across those steps. Two concurrent bookings for the last unit can both pass the read
and both insert (a time-of-check/time-of-use race), overbooking the inventory.

**Current model.** Single-threaded manual testing (and the deterministic smoke) never
interleaves two writes on the same product/window, so the race is masked in practice.

**Why deferred.** Closing it (row locks / `SELECT … FOR UPDATE` on inventory, or a
serializable isolation boundary, or a DB-level exclusion constraint) is a real
concurrency-correctness slice with its own design and test surface. It is **flagged, not
fixed, by deliberate choice** for the POC, where no concurrent load exists.

**If revisited.** A future hardening stage adds write-time locking on the inventory read
inside the existing INV-003 revalidation transaction — additive to the strategy seam, not
a schema change to the booking model.

**Guardrail.** Do not claim availability is concurrency-safe. Any stage that introduces
real concurrent write load must close this *first* or the overbooking becomes reachable.

---

## 3. Inherited POC deferrals (from the charter, recorded here for one-stop visibility)

These are settled in HS-01/HS-02 and listed here so this file is the single index. They
are not re-argued; see the linked PRD for rationale.

- **Holds / drafts (TTL'd inventory reservation).** Not built. The availability seam is
  kept clean so a hold is purely additive later ("another thing that consumes
  availability"). (HS-01 §Human-in-the-Loop, HS-02 Out of scope.)
- **Dynamic pricing / yield management.** Strategy calculates a single price snapshot at
  booking time; no seasonal/demand pricing. (HS-02.)
- **Cross-vertical bundle discounts.** Folio sums line prices; no bundle logic. (HS-02.)
- **Multi-capture / incremental authorisation.** One auth → at most one capture.
  Partial-capture-in is supported; multi-capture-out is not. (HS-03 Finance.)
- **`pay-web` hosted checkout.** Deferred (PSP-014); the customer-paid webhook is driven
  by the test seam in the smoke harness, not a human-clickable page, in the current build.
- **Real auth / real money / multi-currency.** Stubbed `X-Human-Auth` presence gate;
  money is always BIGINT minor units + currency code; single currency.

---

## Changelog

| Date | Entry | Change |
|------|-------|--------|
| 2026-06-14 | §1 Spa therapists; §2 overbooking; §3 index | Initial consolidation. §2 supersedes the dangling `KNOWN_LIMITATION_OVERBOOKING.md` reference in `README.md`. |