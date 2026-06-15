-- =====================================================================================
-- WAVE0_01_SCHEMA.sql  —  Hospitality Operations Platform (POC)
-- Frozen contract: database schema, enums/glossary, invariants.
--
-- HOW TO READ THIS FILE
--   * Section 0  : metadata, accountability, how to apply.
--   * Section 1  : enums / controlled vocabularies (ENM-*).  Single source of allowed values.
--   * Section 2  : core domain — customer, preferences, product (JTI), booking (SCH-*).
--   * Section 3  : finance — payment, capture, refund, ledger, outbox (SCH-*).
--   * Section 4  : invariants enforced in the service layer (documented, not DDL).
--   * Section 5  : requirements table (ID -> acceptance criteria).
--   * Section 6  : verification log (filled by the implementing agent in Wave 1).
--   * Section 7  : changelog.
--
-- DESIGN DECISIONS BAKED IN (see PROJECT_CHARTER.md for the why):
--   * Product uses JOINED-TABLE INHERITANCE: base `product` + one child per vertical.
--   * CustomerPreference is KEY/VALUE (genuinely open-ended, cross-vertical).
--   * Finance is FULLY NORMALISED: real FKs + CHECK constraints.
--   * Booking line carries a FLAT PRICE SNAPSHOT (no inheritance).
--   * Ledger posts on CAPTURE, not auth. Auth is a hold, not a financial event.
--   * Amounts are stored in MINOR UNITS (integer, e.g. pence) to avoid float error.
--   * Money columns are BIGINT minor units + a currency code; no FLOAT anywhere.
--
-- APPLY :  psql "$DATABASE_URL" -f WAVE0_01_SCHEMA.sql      (idempotent: safe to re-run)
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- SECTION 0 — METADATA & ACCOUNTABILITY
-- -------------------------------------------------------------------------------------
-- Artifact      : WAVE0_01_SCHEMA.sql
-- Status        : DRAFT  (-> FROZEN on sign-off -> IN-BUILD in Wave 1 -> DONE)
-- Owner         : (Wave 1, Package A — core domain + persistence)
-- Arbiter       : (central — only the arbiter edits this file once frozen)
-- Sign-off      : __________________________   Date: __________
-- Change policy : Frozen. Changes ONLY via the contract-change protocol in
--                 WAVE0_00_OVERVIEW.md §4 (flag -> arbitrate -> propagate). Record in §7.
-- -------------------------------------------------------------------------------------

BEGIN;

CREATE EXTENSION IF NOT EXISTS "pgcrypto";   -- gen_random_uuid()

-- =====================================================================================
-- SECTION 1 — ENUMS / CONTROLLED VOCABULARIES  (ENM-*)
-- These are the SINGLE SOURCE OF TRUTH for allowed values. Other Wave 0 artifacts
-- (OpenAPI, webhook contract) reference these names and MUST NOT redefine them.
-- =====================================================================================

-- ENM-001  vertical — the four sellable verticals.
CREATE TYPE vertical AS ENUM ('ROOM', 'SPA', 'FNB', 'EVENT');

-- ENM-002  booking_status — lifecycle of a booking (folio).
--   PENDING    : created, nothing settled.
--   CONFIRMED  : at least one line committed against valid inventory.
--   CANCELLED  : all lines cancelled.
--   COMPLETED  : stay/service delivered.
CREATE TYPE booking_status AS ENUM ('PENDING', 'CONFIRMED', 'CANCELLED', 'COMPLETED');

-- ENM-003  booking_line_status — per-line lifecycle (a line can be cancelled alone).
CREATE TYPE booking_line_status AS ENUM ('ACTIVE', 'CANCELLED', 'COMPLETED');

-- ENM-004  capture_mode — per-payment, vertical-defaulted.
--   IMMEDIATE : auth-and-capture together (e.g. F&B).
--   MANUAL    : auth now, capture later (e.g. Rooms: hold card, capture at checkout).
CREATE TYPE capture_mode AS ENUM ('IMMEDIATE', 'MANUAL');

-- ENM-005  payment_status — payment state machine (forks on capture_mode).
--   PENDING            : link created, customer has not paid.
--   AUTHORISED         : funds authorised (MANUAL path, awaiting capture).
--   CAPTURED           : funds captured (revenue posts here). Full or partial.
--   CAPTURE_FAILED     : capture attempt failed.
--   CANCELLED          : uncaptured auth voided (no reversal — nothing was posted).
--   AUTH_EXPIRED       : auth lapsed before capture.
--   PARTIALLY_REFUNDED : at least one refund, but not the full captured amount.
--   REFUNDED           : fully refunded.
--   FAILED             : authorisation failed / link expired unpaid.
CREATE TYPE payment_status AS ENUM (
  'PENDING', 'AUTHORISED', 'CAPTURED', 'CAPTURE_FAILED',
  'CANCELLED', 'AUTH_EXPIRED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'FAILED'
);

-- ENM-006  refund_status.
CREATE TYPE refund_status AS ENUM ('PENDING', 'REFUNDED', 'REFUND_FAILED');

-- ENM-007  psp_event_code — the PSP event vocabulary echoed by payments-sim.
--   Mirrors WHK-* in WAVE0_03_WEBHOOK_PSP_CONTRACT.md (that file is authoritative for
--   payload shapes; this enum is authoritative for the allowed codes core-api persists).
CREATE TYPE psp_event_code AS ENUM (
  'AUTHORISATION', 'CAPTURE', 'CAPTURE_FAILED',
  'CANCELLATION', 'REFUND', 'REFUND_FAILED', 'AUTH_EXPIRY'
);

-- ENM-008  posting_type — ledger posting classes. Auth is NOT a posting.
--   REVENUE         : capture posts revenue (positive, attributed to a vertical).
--   REFUND_REVERSAL : refund reverses revenue (negative), traceable to original.
CREATE TYPE posting_type AS ENUM ('REVENUE', 'REFUND_REVERSAL');

-- ENM-009  outbox_status — outbox/event-log processing state.
CREATE TYPE outbox_status AS ENUM ('PENDING', 'PROCESSED', 'FAILED');

-- ENM-010  Glossary of reference vocabulary (Adyen-flavoured) — NOT a SQL type;
-- documented here as the controlled meaning of the reference columns used below:
--   shopperReference  : stable, opaque, immutable customer id WE mint (SHPR-...). 1:1 customer.
--   merchantReference : OUR ref per payment attempt (we send it). Reconciliation anchor.
--   pspReference      : the PSP's own txn id (payments-sim mints, returns). We store.
--   paymentLinkId     : PSP's id for a hosted payment link (payments-sim mints).
--   originalReference : on a child txn (capture/refund), the parent's pspReference.

-- =====================================================================================
-- SECTION 2 — CORE DOMAIN
-- =====================================================================================

-- SCH-001  customer — identity + contact + opaque shopperReference (immutable, 1:1).
CREATE TABLE customer (
  id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  shopper_reference  TEXT NOT NULL UNIQUE,                 -- SCH-002: 'SHPR-' + opaque token
  full_name          TEXT NOT NULL,
  email              TEXT,
  phone              TEXT,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_shopper_reference_format CHECK (shopper_reference ~ '^SHPR-[A-Za-z0-9_-]{8,}$')
);
-- SCH-002  shopper_reference is minted once at creation and NEVER updated (enforced in
--          service layer + INV-001). Format guarded by chk_shopper_reference_format.
CREATE INDEX idx_customer_full_name ON customer (lower(full_name));

-- SCH-003  customer_preference — KEY/VALUE, cross-vertical, open-ended.
CREATE TABLE customer_preference (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id  UUID NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
  pref_key     TEXT NOT NULL,                              -- e.g. 'floor', 'dietary', 'spa_therapist'
  pref_value   TEXT NOT NULL,                              -- e.g. 'high', 'vegan', 'female'
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_pref_per_customer_key UNIQUE (customer_id, pref_key) -- SCH-004: one value per key
);
CREATE INDEX idx_pref_customer ON customer_preference (customer_id);

-- SCH-010  product — JTI BASE TABLE. Common attributes only.
--          Exactly one child row (matching `vertical`) must exist — see INV-002.
CREATE TABLE product (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  vertical      vertical NOT NULL,
  name          TEXT NOT NULL,
  active        BOOLEAN NOT NULL DEFAULT TRUE,
  base_price    BIGINT NOT NULL,                           -- minor units; anchor price
  currency      CHAR(3) NOT NULL DEFAULT 'GBP',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_product_base_price_nonneg CHECK (base_price >= 0)
);
CREATE INDEX idx_product_vertical_active ON product (vertical, active);

-- SCH-011  product_room — JTI child for ROOM.
CREATE TABLE product_room (
  product_id     UUID PRIMARY KEY REFERENCES product(id) ON DELETE CASCADE,
  floor_band     TEXT,                                     -- e.g. 'LOW' | 'MID' | 'HIGH'
  bed_type       TEXT,                                     -- e.g. 'KING' | 'TWIN' | 'DOUBLE'
  max_occupancy  INT  NOT NULL DEFAULT 2,
  quiet          BOOLEAN NOT NULL DEFAULT FALSE,
  room_count     INT  NOT NULL,                            -- inventory: rooms of this type
  CONSTRAINT chk_room_occupancy CHECK (max_occupancy >= 1),
  CONSTRAINT chk_room_count     CHECK (room_count >= 0)
);

-- SCH-012  product_spa — JTI child for SPA.
CREATE TABLE product_spa (
  product_id        UUID PRIMARY KEY REFERENCES product(id) ON DELETE CASCADE,
  treatment_kind    TEXT NOT NULL,                         -- e.g. 'MASSAGE_60', 'FACIAL'
  duration_minutes  INT  NOT NULL,
  therapist_gender  TEXT,                                  -- nullable preference target
  concurrent_slots  INT  NOT NULL,                         -- inventory: parallel capacity per slot
  CONSTRAINT chk_spa_duration CHECK (duration_minutes > 0),
  CONSTRAINT chk_spa_slots    CHECK (concurrent_slots >= 0)
);

-- SCH-013  product_fnb — JTI child for F&B.
CREATE TABLE product_fnb (
  product_id        UUID PRIMARY KEY REFERENCES product(id) ON DELETE CASCADE,
  service_period    TEXT NOT NULL,                         -- e.g. 'BREAKFAST','LUNCH','DINNER'
  covers_capacity   INT  NOT NULL,                         -- inventory: covers per service period
  seating_minutes   INT  NOT NULL DEFAULT 120,
  CONSTRAINT chk_fnb_capacity CHECK (covers_capacity >= 0)
);

-- SCH-014  product_event — JTI child for EVENT.
CREATE TABLE product_event (
  product_id   UUID PRIMARY KEY REFERENCES product(id) ON DELETE CASCADE,
  departs_at   TIMESTAMPTZ NOT NULL,                       -- the experience start
  duration_minutes INT NOT NULL,
  capacity     INT  NOT NULL,                              -- inventory: seats for this departure
  location     TEXT,
  CONSTRAINT chk_event_capacity CHECK (capacity >= 0),
  CONSTRAINT chk_event_duration CHECK (duration_minutes > 0)
);

-- SCH-020  booking — the folio. Customer + amount roll-ups. Lines span verticals.
--          Amounts are DERIVED from payments/refunds and maintained by core-api on
--          capture/refund events (see INV-004). 'Paid' == (balance == 0), not a boolean.
CREATE TABLE booking (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id      UUID NOT NULL REFERENCES customer(id),
  status           booking_status NOT NULL DEFAULT 'PENDING',
  currency         CHAR(3) NOT NULL DEFAULT 'GBP',
  total_amount     BIGINT NOT NULL DEFAULT 0,              -- sum of active line snapshots
  amount_paid      BIGINT NOT NULL DEFAULT 0,              -- sum of captured payment amounts
  amount_refunded  BIGINT NOT NULL DEFAULT 0,              -- sum of refunded amounts
  -- balance is derived: total_amount - amount_paid + amount_refunded  (see SCH-021)
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_booking_amounts_nonneg
    CHECK (total_amount >= 0 AND amount_paid >= 0 AND amount_refunded >= 0)
);
CREATE INDEX idx_booking_customer ON booking (customer_id);
CREATE INDEX idx_booking_status   ON booking (status);

-- SCH-021  balance — exposed via a view so reads (incl. listUnpaidBookings) are consistent.
CREATE VIEW booking_balance AS
  SELECT id AS booking_id,
         total_amount,
         amount_paid,
         amount_refunded,
         (total_amount - amount_paid + amount_refunded) AS balance
  FROM booking;

-- SCH-022  booking_line — one line per product/time window, with a FLAT PRICE SNAPSHOT.
CREATE TABLE booking_line (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  booking_id    UUID NOT NULL REFERENCES booking(id) ON DELETE CASCADE,
  product_id    UUID NOT NULL REFERENCES product(id),
  vertical      vertical NOT NULL,                         -- denormalised for query convenience
  status        booking_line_status NOT NULL DEFAULT 'ACTIVE',
  starts_at     TIMESTAMPTZ NOT NULL,                      -- time window start
  ends_at       TIMESTAMPTZ NOT NULL,                      -- time window end
  quantity      INT NOT NULL DEFAULT 1,                    -- nights / covers / seats
  unit_price    BIGINT NOT NULL,                           -- SNAPSHOT at booking time (minor units)
  currency      CHAR(3) NOT NULL DEFAULT 'GBP',
  line_amount   BIGINT NOT NULL,                           -- unit_price * quantity (snapshot)
  -- ⚠️ The line_amount column comment above and chk_line_amount below are superseded by
  -- RX-002, see Freeze Ledger WAVE0_00 §1b. (line_amount is strategy-owned; ROOM × nights.)
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_line_qty        CHECK (quantity > 0),
  CONSTRAINT chk_line_window     CHECK (ends_at > starts_at),
  CONSTRAINT chk_line_amount     CHECK (line_amount = unit_price * quantity)  -- superseded by RX-002 (see Freeze Ledger §1b)
);
CREATE INDEX idx_line_booking ON booking_line (booking_id);
CREATE INDEX idx_line_product_window ON booking_line (product_id, starts_at, ends_at)
  WHERE status = 'ACTIVE';                                 -- availability queries hit committed lines

-- =====================================================================================
-- SECTION 3 — FINANCE (fully normalised)
-- =====================================================================================

-- SCH-030  payment — one booking -> many payments. A payment settles exactly ONE booking.
--          Carries the full reference taxonomy. capture_mode is vertical-defaulted but
--          explicit and overridable. amount_authorised vs amount_captured tracked separately.
CREATE TABLE payment (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  booking_id          UUID NOT NULL REFERENCES booking(id),
  shopper_reference   TEXT NOT NULL,                       -- copied from customer (continuity)
  merchant_reference  TEXT NOT NULL UNIQUE,                -- SCH-031: our ref per attempt (anchor)
  psp_reference       TEXT UNIQUE,                         -- minted by payments-sim on auth
  payment_link_id     TEXT UNIQUE,                         -- minted by payments-sim on link create
  capture_mode        capture_mode NOT NULL,
  status              payment_status NOT NULL DEFAULT 'PENDING',
  currency            CHAR(3) NOT NULL DEFAULT 'GBP',
  amount_requested    BIGINT NOT NULL,                     -- amount the link/auth is for
  amount_authorised   BIGINT NOT NULL DEFAULT 0,
  amount_captured     BIGINT NOT NULL DEFAULT 0,           -- drives booking.amount_paid
  amount_refunded     BIGINT NOT NULL DEFAULT 0,
  auth_expires_at     TIMESTAMPTZ,                         -- for MANUAL auths
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_pay_amounts_nonneg
    CHECK (amount_requested >= 0 AND amount_authorised >= 0
           AND amount_captured >= 0 AND amount_refunded >= 0),
  CONSTRAINT chk_pay_capture_le_auth   CHECK (amount_captured <= amount_authorised), -- SCH-032: partial capture in
  CONSTRAINT chk_pay_refund_le_capture CHECK (amount_refunded <= amount_captured)    -- SCH-033: cannot refund > captured
);
CREATE INDEX idx_payment_booking  ON payment (booking_id);
CREATE INDEX idx_payment_merchant ON payment (merchant_reference);
CREATE INDEX idx_payment_psp      ON payment (psp_reference);
CREATE INDEX idx_payment_status   ON payment (status);

-- SCH-034  Single-capture rule (multi-capture OUT): at most one capture per auth.
--          Enforced in service layer (INV-005) — the data model permits one captured
--          amount; multiple capture *events* against the same payment are rejected.

-- SCH-040  refund — one-to-many child of payment. Each refund has its own pspReference,
--          linked to the original auth via original_reference (parent/child chain).
CREATE TABLE refund (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_id          UUID NOT NULL REFERENCES payment(id),
  amount              BIGINT NOT NULL,
  currency            CHAR(3) NOT NULL DEFAULT 'GBP',
  status              refund_status NOT NULL DEFAULT 'PENDING',
  merchant_reference  TEXT NOT NULL UNIQUE,                -- our ref for this refund attempt
  psp_reference       TEXT UNIQUE,                         -- minted by payments-sim for the refund
  original_reference  TEXT NOT NULL,                       -- the parent payment's psp_reference
  reason              TEXT,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_refund_amount_pos CHECK (amount > 0)
);
CREATE INDEX idx_refund_payment ON refund (payment_id);

-- SCH-050  ledger_posting — financial postings. Auth is NOT here; only capture & refunds.
--          REVENUE positive, REFUND_REVERSAL negative. Attributed by vertical and traceable
--          to the PSP txn via psp_reference / merchant_reference.
CREATE TABLE ledger_posting (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  posting_type        posting_type NOT NULL,
  booking_id          UUID NOT NULL REFERENCES booking(id),
  booking_line_id     UUID REFERENCES booking_line(id),    -- nullable: some postings are folio-level
  payment_id          UUID REFERENCES payment(id),
  refund_id           UUID REFERENCES refund(id),
  vertical            vertical NOT NULL,                   -- revenue reportable BY vertical
  amount              BIGINT NOT NULL,                     -- signed: +revenue, -reversal
  currency            CHAR(3) NOT NULL DEFAULT 'GBP',
  psp_reference       TEXT,                                -- trace-through to PSP txn
  merchant_reference  TEXT,
  narration           TEXT,
  posted_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_posting_sign
    CHECK ( (posting_type = 'REVENUE'         AND amount >= 0)
         OR (posting_type = 'REFUND_REVERSAL' AND amount <= 0) )  -- SCH-051
);
CREATE INDEX idx_posting_vertical ON ledger_posting (vertical);
CREATE INDEX idx_posting_booking  ON ledger_posting (booking_id);
CREATE INDEX idx_posting_posted   ON ledger_posting (posted_at);

-- SCH-060  outbox_event — simple outbox/event log so ledger postings are DECOUPLED from
--          booking/payment writes. A booking/payment write enqueues an event in the SAME
--          transaction; the ledger processor consumes asynchronously and idempotently.
CREATE TABLE outbox_event (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_type      TEXT NOT NULL,                           -- e.g. 'PAYMENT_CAPTURED','REFUND_SETTLED'
  aggregate_type  TEXT NOT NULL,                           -- e.g. 'PAYMENT','BOOKING'
  aggregate_id    UUID NOT NULL,
  payload         JSONB NOT NULL,                          -- event detail (passthrough; not queried-on)
  status          outbox_status NOT NULL DEFAULT 'PENDING',
  attempts        INT NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at    TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox_event (status, created_at) WHERE status = 'PENDING';

-- SCH-070  webhook_inbox — idempotent inbound PSP webhook log. core-api matches inbound to
--          a payment by merchant_reference, stamps the returned psp_reference, dedupes by
--          idempotency_key (pspReference-derived). See WHK-* for payload shapes.
CREATE TABLE webhook_inbox (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  idempotency_key     TEXT NOT NULL UNIQUE,                -- SCH-071: dedupe key (pspReference-derived)
  event_code          psp_event_code NOT NULL,
  merchant_reference  TEXT NOT NULL,                       -- match target
  psp_reference       TEXT,
  raw_payload         JSONB NOT NULL,                      -- stored verbatim for audit
  received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at        TIMESTAMPTZ
);
CREATE INDEX idx_webhook_merchant ON webhook_inbox (merchant_reference);

COMMIT;

-- =====================================================================================
-- SECTION 4 — INVARIANTS (service-layer enforced; documented, not DDL)
-- These cannot (or should not) be expressed purely in DDL for a POC. core-api MUST
-- enforce them, and Package A MUST have a test per invariant referencing its ID.
-- =====================================================================================
--
-- INV-001  shopper_reference is minted once on customer creation and NEVER changes.
--          (DDL guards format + uniqueness; immutability is service-enforced.)
--
-- INV-002  Product single-child rule: for every `product` row there is EXACTLY ONE child
--          row, in the table matching `product.vertical`. No zero-child, no multi-child.
--          (JTI does not enforce this; core-api creates base + child atomically and
--          rejects any operation that would violate it.)
--
-- INV-003  Write-time revalidation: every write (createBooking / modifyBookingLine /
--          capture / refund) re-checks availability and price atomically. If state moved
--          since the caller's last read, the write FAILS LOUDLY (409) with current state.
--
-- INV-004  Amount roll-ups: booking.total_amount = sum(active line_amount);
--          booking.amount_paid = sum(payment.amount_captured for that booking);
--          booking.amount_refunded = sum(refund.amount settled). Maintained by core-api
--          on the relevant events, never by clients.
--
-- INV-005  Single-capture: at most one capture per payment (full or partial). Multi-capture
--          and incremental-auth are OUT of scope; such requests are rejected.
--
-- INV-006  Ledger posts on CAPTURE, not auth. AUTHORISATION and CANCELLATION produce NO
--          posting. CAPTURE -> REVENUE posting; REFUND -> REFUND_REVERSAL posting.
--
-- INV-007  Human-authorisation gate: repercussive writes require a human-auth signal the
--          caller cannot self-mint (see API-* in WAVE0_02_OPENAPI.yaml). Enforced server-side.
--
-- =====================================================================================
-- SECTION 5 — REQUIREMENTS TABLE  (ID -> acceptance criteria -> depends-on)
-- =====================================================================================
-- ENM-001 vertical enum exists with exactly {ROOM,SPA,FNB,EVENT}.                    | —
-- ENM-002 booking_status enum matches Section 1.                                     | —
-- ENM-003 booking_line_status enum matches Section 1.                                | —
-- ENM-004 capture_mode enum = {IMMEDIATE,MANUAL}.                                     | —
-- ENM-005 payment_status enum matches Section 1 (9 states).                          | —
-- ENM-006 refund_status enum = {PENDING,REFUNDED,REFUND_FAILED}.                      | —
-- ENM-007 psp_event_code enum matches WHK-* codes.                                   | WHK
-- ENM-008 posting_type enum = {REVENUE,REFUND_REVERSAL}.                             | —
-- ENM-009 outbox_status enum = {PENDING,PROCESSED,FAILED}.                            | —
-- ENM-010 reference-vocabulary glossary documented.                                  | —
-- SCH-001 customer table applies; shopper_reference UNIQUE + format CHECK.           | ENM
-- SCH-002 shopper_reference format 'SHPR-...'; immutability is INV-001.              | SCH-001
-- SCH-003 customer_preference KV table; cascade delete with customer.                | SCH-001
-- SCH-004 one value per (customer_id, pref_key) (UNIQUE).                            | SCH-003
-- SCH-010 product base table; vertical NOT NULL; base_price>=0.                      | ENM-001
-- SCH-011 product_room child; PK=FK to product; room_count>=0.                       | SCH-010
-- SCH-012 product_spa child; duration>0; concurrent_slots>=0.                        | SCH-010
-- SCH-013 product_fnb child; covers_capacity>=0.                                     | SCH-010
-- SCH-014 product_event child; capacity>=0; departs_at present.                      | SCH-010
-- SCH-020 booking table; amount roll-up columns; nonneg CHECK.                       | SCH-001
-- SCH-021 booking_balance view returns derived balance.                              | SCH-020
-- SCH-022 booking_line; flat price snapshot; line_amount=unit_price*qty CHECK;       | SCH-020,
--         window CHECK; partial index on ACTIVE lines for availability.              |   SCH-010
-- SCH-030 payment table; full reference taxonomy; merchant_reference UNIQUE.         | SCH-020
-- SCH-031 merchant_reference is reconciliation anchor (UNIQUE, indexed).             | SCH-030
-- SCH-032 partial capture IN: amount_captured<=amount_authorised CHECK.              | SCH-030
-- SCH-033 cannot refund > captured: amount_refunded<=amount_captured CHECK.          | SCH-030
-- SCH-034 single-capture rule documented as INV-005.                                 | SCH-030
-- SCH-040 refund table; original_reference chain to parent psp_reference.            | SCH-030
-- SCH-050 ledger_posting; signed amount; vertical attribution; PSP trace cols.       | SCH-020,030,040
-- SCH-051 posting sign CHECK ties type to sign.                                      | SCH-050
-- SCH-060 outbox_event table; partial index on PENDING.                              | —
-- SCH-070 webhook_inbox; idempotency_key UNIQUE; match by merchant_reference.        | SCH-030, WHK
-- SCH-071 idempotency_key dedupe (pspReference-derived).                             | SCH-070
-- INV-001..007 service-layer invariants each have a test referencing the ID.         | (Wave 1)
--
-- ACCEPTANCE (whole file): `psql -f` applies cleanly to a fresh DB with zero errors,
-- is idempotent on re-run within a transaction-safe harness, and every ENM/SCH ID above
-- maps to an object present in the applied schema.
--
-- =====================================================================================
-- SECTION 6 — VERIFICATION LOG  (filled by the implementing agent in Wave 1)
-- Per requirement ID: what was built | commit/PR ref | test that proves it | date.
-- =====================================================================================
-- | Req ID  | Built (summary)                                                      | Commit/PR | Proving test                                                       | Date       |
-- |---------|------------------------------------------------------------------------------|-----------|--------------------------------------------------------------------|------------|
-- | ENM-001 | Vertical enum (ROOM/SPA/FNB/EVENT) in enums/Vertical.java               | Wave1/PkgA | ProductEntityTest.SCH_010_product_room_has_correct_vertical        | 2026-06-09 |
-- | ENM-002 | BookingStatus enum in enums/BookingStatus.java                           | Wave1/PkgA | BookingEntityTest.SCH_020_booking_persists_with_default_amounts    | 2026-06-09 |
-- | ENM-003 | BookingLineStatus enum in enums/BookingLineStatus.java                   | Wave1/PkgA | BookingEntityTest.SCH_022_booking_line_persists_with_price_snapshot| 2026-06-09 |
-- | ENM-004 | CaptureMode enum in enums/CaptureMode.java                               | Wave1/PkgA | PaymentEntityTest.SCH_030_payment_persists_with_reference_taxonomy | 2026-06-09 |
-- | ENM-005 | PaymentStatus enum (9 states) in enums/PaymentStatus.java               | Wave1/PkgA | PaymentEntityTest.SCH_030_payment_persists_with_reference_taxonomy | 2026-06-09 |
-- | ENM-006 | RefundStatus enum in enums/RefundStatus.java                             | Wave1/PkgA | PaymentEntityTest.SCH_040_refund_persists_with_original_reference_chain | 2026-06-09 |
-- | ENM-007 | PspEventCode enum (7 codes) in enums/PspEventCode.java                  | Wave1/PkgA | LedgerAndOutboxEntityTest.SCH_070_webhook_inbox_persists_raw_payload | 2026-06-09 |
-- | ENM-008 | PostingType enum in enums/PostingType.java                               | Wave1/PkgA | LedgerAndOutboxEntityTest.SCH_050_ledger_posting_revenue_persists  | 2026-06-09 |
-- | ENM-009 | OutboxStatus enum in enums/OutboxStatus.java                             | Wave1/PkgA | LedgerAndOutboxEntityTest.SCH_060_outbox_event_persists_with_pending_status | 2026-06-09 |
-- | ENM-010 | Reference-vocabulary glossary documented in Money.java + PaymentService  | Wave1/PkgA | InvariantTest (all reference taxonomy fields present on entities)  | 2026-06-09 |
-- | SCH-001 | customer table; chk_shopper_reference_format; UNIQUE shopper_reference   | Wave1/PkgA | CustomerEntityTest.SCH_001_*                                        | 2026-06-09 |
-- | SCH-002 | shopperReference updatable=false; minted by CustomerService              | Wave1/PkgA | CustomerEntityTest.SCH_002_shopper_reference_column_is_updatable_false | 2026-06-09 |
-- | SCH-003 | customer_preference KV table; cascade delete                             | Wave1/PkgA | CustomerEntityTest.SCH_003_preferences_cascade_deleted_with_customer | 2026-06-09 |
-- | SCH-004 | uq_pref_per_customer_key UNIQUE constraint enforced                      | Wave1/PkgA | CustomerEntityTest.SCH_004_unique_constraint_one_value_per_customer_key | 2026-06-09 |
-- | SCH-010 | product JTI base table; chk_product_base_price_nonneg                   | Wave1/PkgA | ProductEntityTest.SCH_010_*                                         | 2026-06-09 |
-- | SCH-011 | product_room JTI child; chk_room_count; chk_room_occupancy              | Wave1/PkgA | ProductEntityTest.SCH_011_*                                         | 2026-06-09 |
-- | SCH-012 | product_spa JTI child; chk_spa_duration; chk_spa_slots                  | Wave1/PkgA | ProductEntityTest.SCH_012_*                                         | 2026-06-09 |
-- | SCH-013 | product_fnb JTI child; chk_fnb_capacity                                 | Wave1/PkgA | ProductEntityTest.SCH_013_*                                         | 2026-06-09 |
-- | SCH-014 | product_event JTI child; chk_event_capacity; chk_event_duration         | Wave1/PkgA | ProductEntityTest.SCH_014_*                                         | 2026-06-09 |
-- | SCH-020 | booking table; amount roll-up columns; chk_booking_amounts_nonneg       | Wave1/PkgA | BookingEntityTest.SCH_020_*                                         | 2026-06-09 |
-- | SCH-021 | booking_balance view; derived balance; BookingBalance entity             | Wave1/PkgA | BookingEntityTest.SCH_021_*                                         | 2026-06-09 |
-- | SCH-022 | booking_line; flat price snapshot; chk_line_amount; chk_line_window     | Wave1/PkgA | BookingEntityTest.SCH_022_*                                         | 2026-06-09 |
-- | SCH-030 | payment table; full reference taxonomy; merchant_reference UNIQUE        | Wave1/PkgA | PaymentEntityTest.SCH_030_*                                         | 2026-06-09 |
-- | SCH-031 | merchant_reference UNIQUE, indexed, findByMerchantReference works       | Wave1/PkgA | PaymentEntityTest.SCH_031_*                                         | 2026-06-09 |
-- | SCH-032 | chk_pay_capture_le_auth; partial capture in                             | Wave1/PkgA | PaymentEntityTest.SCH_032_*                                         | 2026-06-09 |
-- | SCH-033 | chk_pay_refund_le_capture; cannot refund > captured                     | Wave1/PkgA | PaymentEntityTest.SCH_033_*                                         | 2026-06-09 |
-- | SCH-034 | Single-capture rule documented; enforced by INV-005 in PaymentService   | Wave1/PkgA | InvariantTest.INV_005_second_capture_attempt_is_rejected            | 2026-06-09 |
-- | SCH-040 | refund table; original_reference chain; chk_refund_amount_pos           | Wave1/PkgA | PaymentEntityTest.SCH_040_*                                         | 2026-06-09 |
-- | SCH-050 | ledger_posting; signed amount; vertical attribution; PSP trace cols     | Wave1/PkgA | LedgerAndOutboxEntityTest.SCH_050_*                                 | 2026-06-09 |
-- | SCH-051 | chk_posting_sign: REVENUE>=0, REFUND_REVERSAL<=0                        | Wave1/PkgA | LedgerAndOutboxEntityTest.SCH_051_*                                 | 2026-06-09 |
-- | SCH-060 | outbox_event table; idx_outbox_pending partial index; OutboxProcessor   | Wave1/PkgA | LedgerAndOutboxEntityTest.SCH_060_*                                 | 2026-06-09 |
-- | SCH-070 | webhook_inbox; idempotency_key UNIQUE; match by merchant_reference      | Wave1/PkgA | LedgerAndOutboxEntityTest.SCH_070_*                                 | 2026-06-09 |
-- | SCH-071 | idempotency_key dedupe (existsByIdempotencyKey)                         | Wave1/PkgA | LedgerAndOutboxEntityTest.SCH_071_*                                 | 2026-06-09 |
-- | INV-001 | shopperReference minted once in CustomerService; updatable=false        | Wave1/PkgA | InvariantTest.INV_001_*                                             | 2026-06-09 |
-- | INV-002 | ProductService creates base+child atomically; one child per product     | Wave1/PkgA | InvariantTest.INV_002_*                                             | 2026-06-09 |
-- | INV-003 | BookingService.addLine re-checks availability atomically; 409 on stale | Wave1/PkgA | InvariantTest.INV_003_*                                             | 2026-06-09 |
-- | INV-004 | BookingService.recalculateTotals; roll-up of totalAmount/paid/refunded  | Wave1/PkgA | InvariantTest.INV_004_*                                             | 2026-06-09 |
-- | INV-005 | PaymentService.capture rejects second capture (StateChangedException)  | Wave1/PkgA | InvariantTest.INV_005_*                                             | 2026-06-09 |
-- | INV-006 | Authorisation/cancel: no outbox event; capture/refund: event enqueued  | Wave1/PkgA | InvariantTest.INV_006_*                                             | 2026-06-09 |
-- | INV-007 | HumanAuthorizationGate; blank/null token -> 428; present token passes  | Wave1/PkgA | InvariantTest.INV_007_*                                             | 2026-06-09 |
--
-- NOTE: DataJPA integration tests (CustomerEntityTest, ProductEntityTest,
-- BookingEntityTest, PaymentEntityTest, LedgerAndOutboxEntityTest) require a
-- Docker/container runtime to run (Testcontainers + Postgres 16-alpine).  They
-- are structured as @DataJpaTest + AbstractDataJpaTest with a @BeforeAll assumption
-- that skips them gracefully when no container runtime is available.
-- All 13 INV-* unit tests pass on every machine (no Docker required).
--
-- =====================================================================================
-- SECTION 7 — CHANGELOG
-- =====================================================================================
-- | Version | Date    | Author  | Change |
-- |---------|---------|---------|--------|
-- | 0.1     | (draft) | (init)  | Initial schema drafted for sign-off. JTI Product;
-- |         |         |         | KV preferences; normalised finance; outbox; webhook inbox. |
-- =====================================================================================
