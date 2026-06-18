# PROPOSAL — API-014 `completeLine` + API-015 `completeFolio` — ✅ APPLIED & FROZEN

> **Status: APPLIED & FROZEN (Desk sign-off, this commit).** §A–§E applied to
> `WAVE0_02_OPENAPI.yaml`; §F rows + §G changelog (v1.5) applied to `WAVE0_00_OVERVIEW.md`;
> the two §1b rows are **FROZEN**. The two open questions (§0) were ratified at freeze:
> nested line-complete path **and** PENDING-folio → 409 both approved as drafted. This file
> is retained as the design/rationale record for the freeze. **Authoritative status lives in
> `WAVE0_00 §1b`**, not here.
>
> _Originally raised as a DRAFT proposal because the endpoints were net-new HTTP surface
> absent from the then-FROZEN `WAVE0_02` (Stage 2 stopped at API-013); per CLAUDE.md's one
> rule + `WAVE0_00 §4`, endpoint behaviour must trace to a FROZEN `API-` ID before code._
>
> **Owner / arbiter:** Desk. **Pairs with:** `DESIGN_FOLIO_COMPLETION.md`, `RX-003`.
> **Depends on (all FROZEN):** ENM-002 (`BookingStatus.COMPLETED`), ENM-003
> (`BookingLineStatus.COMPLETED`), INV-007 (`HumanAuthorizationGate`), RX-003 (`customerOwes`).
>
> **On sign-off, Desk applies §A–§D to `WAVE0_02_OPENAPI.yaml` and §E–§F to
> `WAVE0_00_OVERVIEW.md`, then flips the §1b rows DRAFT→FROZEN.** Only then does Slice 2
> implement, citing API-014/API-015 + `[ENM-002, ENM-003, INV-007, RX-003]`.

---

## 0. Why these two IDs (the gap the one-rule catches)

`DESIGN_FOLIO_COMPLETION.md §6` says "§1b unaffected — additive behaviour over existing
enums." That is true of the **enum transitions** but not of the **HTTP surface**: two new
paths, the INV-007 gate on one, the success bodies, and the **409 failure-body shape**
(which condition failed, which lines, live `customerOwes`) plus **idempotent-200** semantics
are all *endpoint behaviour*. A Desk-owned design note is not a frozen contract. So the
endpoints get real `API-` IDs (next free after API-013).

Two points for Desk to ratify while freezing:
1. **Path shapes** in §A (line-complete nested under its booking so the folio can be re-read
   and returned).
2. **PENDING folio** (empty, no lines): DESIGN only covers `CONFIRMED→COMPLETED`. This
   proposal rejects `completeFolio` on `PENDING` with `409` (not completable). Confirm or
   override.

---

## A. Paths — insert into `paths:` (after the `/bookings/{bookingId}` GET block, ~line 201)

```yaml
  /bookings/{bookingId}/lines/{lineId}/complete:
    post:
      operationId: completeLine
      tags:
      - bookings
      summary: 'Mark a booking line rendered/done (ACTIVE -> COMPLETED). Ungated: posts
        nothing, moves no money (mirrors cancelLine). No folio side effect — completing a
        line never flips the booking.'
      parameters:
      - $ref: '#/components/parameters/BookingId'
      - $ref: '#/components/parameters/LineId'
      responses:
        '200':
          description: 'Line marked COMPLETED. Returns the folio; the line status reflects
            the change and the booking status is unchanged.'
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/FolioResponse'
        '404':
          $ref: '#/components/responses/NotFound'
        '409':
          $ref: '#/components/responses/StateConflict'
  /bookings/{bookingId}/complete:
    post:
      operationId: completeFolio
      tags:
      - bookings
      summary: 'Close out a folio (CONFIRMED -> COMPLETED). Repercussive, INV-007 gated.
        Revalidates C1 (every non-CANCELLED line is COMPLETED) + C2 (customerOwes == 0,
        RX-003) atomically and fails loudly with current state. Idempotent on
        already-COMPLETED; CANCELLED/PENDING -> 409.'
      parameters:
      - $ref: '#/components/parameters/BookingId'
      - $ref: '#/components/parameters/HumanAuth'
      responses:
        '200':
          description: 'Folio completed (CONFIRMED -> COMPLETED), or already COMPLETED
            (idempotent success). Returns the folio.'
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/FolioResponse'
        '404':
          $ref: '#/components/responses/NotFound'
        '409':
          $ref: '#/components/responses/FolioNotCompletable'
        '428':
          $ref: '#/components/responses/HumanAuthRequired'
```

> Note: both POSTs are parameterless (no request body), exactly like `cancelAuthorisation`
> (API-011). `completeLine` carries **no** `HumanAuth` parameter (ungated — Q1/§5 DESIGN).

---

## B. Parameter — add to `components.parameters:` (beside `PaymentId`, ~line 403)

```yaml
    LineId:
      name: lineId
      in: path
      required: true
      schema:
        type: string
        format: uuid
```

---

## C. Response — add to `components.responses:` (after `HumanAuthRequired`, ~line 451)

```yaml
    FolioNotCompletable:
      description: 'completeFolio rejected by write-time revalidation: C1 (a non-CANCELLED
        line is not yet COMPLETED) and/or C2 (customerOwes != 0, RX-003) failed, or the
        folio is in a terminal/ineligible state (CANCELLED or PENDING). No write occurred;
        the body carries the live state so ops-web/the agent can surface it and the caller
        can re-read and retry.'
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/FolioCompletionConflict'
```

---

## D. Schemas — add to `components.schemas:` (beside `StateConflict`, ~line 499)

```yaml
    FolioCompletionState:
      type: object
      required:
      - status
      - customerOwes
      - incompleteLineIds
      properties:
        status:
          $ref: '#/components/schemas/BookingStatus'
          description: The booking's current status, unchanged by the rejected write.
        customerOwes:
          type: integer
          format: int64
          description: 'Live settlement figure (RX-003): max(0, totalAmount - amountPaid),
            minor units. C2 requires this to be 0.'
        incompleteLineIds:
          type: array
          items:
            type: string
            format: uuid
          description: 'C1: every non-CANCELLED line that is not yet COMPLETED (the ACTIVE
            stragglers). Empty when only C2 failed or on a terminal-state attempt.'
      description: 'currentState payload for a completeFolio 409 — names which condition
        failed and echoes the live truth.'
    FolioCompletionConflict:
      allOf:
      - $ref: '#/components/schemas/ApiError'
      - type: object
        properties:
          currentState:
            $ref: '#/components/schemas/FolioCompletionState'
      description: Body of a 409 from completeFolio revalidation or a terminal-state attempt.
```

**Concrete C1+C2 failure body (illustrative):**

```json
{
  "code": "STATE_CONFLICT",
  "message": "Folio not completable: 1 line still ACTIVE; customer owes 10000.",
  "currentState": {
    "status": "CONFIRMED",
    "customerOwes": 10000,
    "incompleteLineIds": ["7c1f...", "..."]
  }
}
```

---

## E. `x-requirements-stage2` — append after the API-013 entry (~line 1207)

```yaml
  - id: API-014
    requirement: Mark a booking line rendered/done (ACTIVE -> COMPLETED). Ungated; no folio side effect.
    acceptance: '200 FolioResponse with the line COMPLETED and the booking status unchanged;
      completing a CANCELLED (terminal) line -> 409; not human-gated (posts nothing, mirrors cancelLine).'
    mapsTo:
    - ENM-003
  - id: API-015
    requirement: Close out a folio (CONFIRMED -> COMPLETED); human-gated; write-time revalidated.
    acceptance: '200 FolioResponse on CONFIRMED -> COMPLETED; idempotent 200 on already-COMPLETED;
      C1 (a non-CANCELLED line not COMPLETED) and/or C2 (customerOwes != 0, RX-003) -> 409
      FolioNotCompletable naming the straggler lineIds + live customerOwes; CANCELLED or PENDING -> 409;
      X-Human-Auth required else 428 (INV-007).'
    mapsTo:
    - ENM-002
    - ENM-003
    - INV-007
    - RX-003
```

---

## F. Proposed Freeze Ledger rows — `WAVE0_00 §1b` (Desk adds on freeze)

```
| `WAVE0_02_OPENAPI.yaml` | API-014 completeLine (Stage 2 close-out / Slice 2) | **FROZEN** | (freeze commit) | — | Additive operational endpoint POST /bookings/{bookingId}/lines/{lineId}/complete — ACTIVE→COMPLETED line transition (ENM-003); ungated (mirrors cancelLine; posts nothing, no money, no inventory); no folio side effect; CANCELLED line → 409. Status → FROZEN · DONE on Slice-2 merge. Proof: FolioCompletionApiTest. |
| `WAVE0_02_OPENAPI.yaml` | API-015 completeFolio (Stage 2 close-out / Slice 2) | **FROZEN** | (freeze commit) | — | Additive operational endpoint POST /bookings/{bookingId}/complete — CONFIRMED→COMPLETED (ENM-002); INV-007 gated (428 else); write-time revalidation C1 (all non-CANCELLED lines COMPLETED) + C2 (customerOwes==0, RX-003) → 409 FolioNotCompletable (names straggler lineIds + live customerOwes); idempotent 200 on COMPLETED; CANCELLED/PENDING → 409. Unblocks Stage 6 finished+settled reads. Status → FROZEN · DONE on Slice-2 merge. Proof: FolioCompletionApiTest. |
```

## G. Proposed §7 changelog row — `WAVE0_00` (Desk adds on freeze)

```
| 1.5 | 2026-06-18 | §1b: froze **API-014 completeLine** + **API-015 completeFolio** (Stage 2 close-out / Slice 2) — additive folio-completion HTTP surface over WAVE0_02. New paths POST /bookings/{id}/lines/{lineId}/complete (ungated, ENM-003) and POST /bookings/{id}/complete (INV-007 gated, ENM-002; write-time C1+C2 revalidation, C2 = RX-003 customerOwes; 409 FolioNotCompletable). New schemas FolioCompletionState/FolioCompletionConflict, LineId param, FolioNotCompletable response, API-014/015 x-requirements-stage2 entries. Pairs with DESIGN_FOLIO_COMPLETION.md. |
```

---

## H. Apply checklist (for Desk at freeze)

- [ ] Insert §A paths, §B param, §C response, §D schemas, §E requirement entries into `WAVE0_02_OPENAPI.yaml`.
- [ ] Confirm the **PENDING → 409** decision (or override) and the path shapes (§0).
- [ ] Add §F rows to `WAVE0_00 §1b`; add §G row to `WAVE0_00 §7`.
- [ ] Validate the YAML (it parses; no dangling `$ref`).
- [ ] Notify the Slice 2 builder: rows are FROZEN → implement, stacked on `rx-003-balance-split-slice-1`.

## I. Scope guards (as drafted; ✅ now resolved by the freeze)

- The contract edits (§A–§G) were applied by Desk **at sign-off**, in one pass — the freeze
  act itself, recorded in `WAVE0_00 §1b`. (Before sign-off this proposal touched no frozen file.)
- **No implementation code** is written by this freeze; the Slice 2 build is the next step,
  stacked on `rx-003-balance-split-slice-1`, citing API-014/API-015 + `[ENM-002, ENM-003, INV-007, RX-003]`.
- **No SCH-/ENM-/INV-/WHK- IDs minted** — the transitions reuse existing ENM-002/003 values;
  no schema change (no migration); no new invariant.
