-- =====================================================================================
-- V1__wave0_schema.sql
-- Derived from: contracts/WAVE0_01_SCHEMA.sql  (frozen — do not modify this migration)
-- Applied by:   Flyway (manages its own transaction; BEGIN/COMMIT stripped from source)
-- =====================================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";   -- gen_random_uuid()

-- =====================================================================================
-- SECTION 1 — ENUMS / CONTROLLED VOCABULARIES  (ENM-*)
-- =====================================================================================

-- ENM-001
CREATE TYPE vertical AS ENUM ('ROOM', 'SPA', 'FNB', 'EVENT');

-- ENM-002
CREATE TYPE booking_status AS ENUM ('PENDING', 'CONFIRMED', 'CANCELLED', 'COMPLETED');

-- ENM-003
CREATE TYPE booking_line_status AS ENUM ('ACTIVE', 'CANCELLED', 'COMPLETED');

-- ENM-004
CREATE TYPE capture_mode AS ENUM ('IMMEDIATE', 'MANUAL');

-- ENM-005
CREATE TYPE payment_status AS ENUM (
  'PENDING', 'AUTHORISED', 'CAPTURED', 'CAPTURE_FAILED',
  'CANCELLED', 'AUTH_EXPIRED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'FAILED'
);

-- ENM-006
CREATE TYPE refund_status AS ENUM ('PENDING', 'REFUNDED', 'REFUND_FAILED');

-- ENM-007
CREATE TYPE psp_event_code AS ENUM (
  'AUTHORISATION', 'CAPTURE', 'CAPTURE_FAILED',
  'CANCELLATION', 'REFUND', 'REFUND_FAILED', 'AUTH_EXPIRY'
);

-- ENM-008
CREATE TYPE posting_type AS ENUM ('REVENUE', 'REFUND_REVERSAL');

-- ENM-009
CREATE TYPE outbox_status AS ENUM ('PENDING', 'PROCESSED', 'FAILED');

-- =====================================================================================
-- SECTION 2 — CORE DOMAIN
-- =====================================================================================

-- SCH-001
CREATE TABLE customer (
  id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  shopper_reference  TEXT NOT NULL UNIQUE,
  full_name          TEXT NOT NULL,
  email              TEXT,
  phone              TEXT,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_shopper_reference_format CHECK (shopper_reference ~ '^SHPR-[A-Za-z0-9_-]{8,}$')
);
CREATE INDEX idx_customer_full_name ON customer (lower(full_name));

-- SCH-003
CREATE TABLE customer_preference (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id  UUID NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
  pref_key     TEXT NOT NULL,
  pref_value   TEXT NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_pref_per_customer_key UNIQUE (customer_id, pref_key)
);
CREATE INDEX idx_pref_customer ON customer_preference (customer_id);

-- SCH-010
CREATE TABLE product (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  vertical      vertical NOT NULL,
  name          TEXT NOT NULL,
  active        BOOLEAN NOT NULL DEFAULT TRUE,
  base_price    BIGINT NOT NULL,
  currency      CHAR(3) NOT NULL DEFAULT 'GBP',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_product_base_price_nonneg CHECK (base_price >= 0)
);
CREATE INDEX idx_product_vertical_active ON product (vertical, active);

-- SCH-011
CREATE TABLE product_room (
  product_id     UUID PRIMARY KEY REFERENCES product(id) ON DELETE CASCADE,
  floor_band     TEXT,
  bed_type       TEXT,
  max_occupancy  INT  NOT NULL DEFAULT 2,
  quiet          BOOLEAN NOT NULL DEFAULT FALSE,
  room_count     INT  NOT NULL,
  CONSTRAINT chk_room_occupancy CHECK (max_occupancy >= 1),
  CONSTRAINT chk_room_count     CHECK (room_count >= 0)
);

-- SCH-012
CREATE TABLE product_spa (
  product_id        UUID PRIMARY KEY REFERENCES product(id) ON DELETE CASCADE,
  treatment_kind    TEXT NOT NULL,
  duration_minutes  INT  NOT NULL,
  therapist_gender  TEXT,
  concurrent_slots  INT  NOT NULL,
  CONSTRAINT chk_spa_duration CHECK (duration_minutes > 0),
  CONSTRAINT chk_spa_slots    CHECK (concurrent_slots >= 0)
);

-- SCH-013
CREATE TABLE product_fnb (
  product_id        UUID PRIMARY KEY REFERENCES product(id) ON DELETE CASCADE,
  service_period    TEXT NOT NULL,
  covers_capacity   INT  NOT NULL,
  seating_minutes   INT  NOT NULL DEFAULT 120,
  CONSTRAINT chk_fnb_capacity CHECK (covers_capacity >= 0)
);

-- SCH-014
CREATE TABLE product_event (
  product_id       UUID PRIMARY KEY REFERENCES product(id) ON DELETE CASCADE,
  departs_at       TIMESTAMPTZ NOT NULL,
  duration_minutes INT NOT NULL,
  capacity         INT  NOT NULL,
  location         TEXT,
  CONSTRAINT chk_event_capacity CHECK (capacity >= 0),
  CONSTRAINT chk_event_duration CHECK (duration_minutes > 0)
);

-- SCH-020
CREATE TABLE booking (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id      UUID NOT NULL REFERENCES customer(id),
  status           booking_status NOT NULL DEFAULT 'PENDING',
  currency         CHAR(3) NOT NULL DEFAULT 'GBP',
  total_amount     BIGINT NOT NULL DEFAULT 0,
  amount_paid      BIGINT NOT NULL DEFAULT 0,
  amount_refunded  BIGINT NOT NULL DEFAULT 0,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_booking_amounts_nonneg
    CHECK (total_amount >= 0 AND amount_paid >= 0 AND amount_refunded >= 0)
);
CREATE INDEX idx_booking_customer ON booking (customer_id);
CREATE INDEX idx_booking_status   ON booking (status);

-- SCH-021
-- WARNING: SCH-021 booking_balance superseded by RX-003 (balance split -> customer_owes + net_revenue).
-- See Freeze Ledger (WAVE0_00_OVERVIEW.md s1b). Do not build against the `balance` column without checking the ledger.
CREATE VIEW booking_balance AS
  SELECT id AS booking_id,
         total_amount,
         amount_paid,
         amount_refunded,
         (total_amount - amount_paid + amount_refunded) AS balance
  FROM booking;

-- SCH-022
CREATE TABLE booking_line (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  booking_id    UUID NOT NULL REFERENCES booking(id) ON DELETE CASCADE,
  product_id    UUID NOT NULL REFERENCES product(id),
  vertical      vertical NOT NULL,
  status        booking_line_status NOT NULL DEFAULT 'ACTIVE',
  starts_at     TIMESTAMPTZ NOT NULL,
  ends_at       TIMESTAMPTZ NOT NULL,
  quantity      INT NOT NULL DEFAULT 1,
  unit_price    BIGINT NOT NULL,
  currency      CHAR(3) NOT NULL DEFAULT 'GBP',
  line_amount   BIGINT NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_line_qty        CHECK (quantity > 0),
  CONSTRAINT chk_line_window     CHECK (ends_at > starts_at),
  CONSTRAINT chk_line_amount     CHECK (line_amount = unit_price * quantity)
);
CREATE INDEX idx_line_booking ON booking_line (booking_id);
CREATE INDEX idx_line_product_window ON booking_line (product_id, starts_at, ends_at)
  WHERE status = 'ACTIVE';

-- =====================================================================================
-- SECTION 3 — FINANCE
-- =====================================================================================

-- SCH-030
CREATE TABLE payment (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  booking_id          UUID NOT NULL REFERENCES booking(id),
  shopper_reference   TEXT NOT NULL,
  merchant_reference  TEXT NOT NULL UNIQUE,
  psp_reference       TEXT UNIQUE,
  payment_link_id     TEXT UNIQUE,
  capture_mode        capture_mode NOT NULL,
  status              payment_status NOT NULL DEFAULT 'PENDING',
  currency            CHAR(3) NOT NULL DEFAULT 'GBP',
  amount_requested    BIGINT NOT NULL,
  amount_authorised   BIGINT NOT NULL DEFAULT 0,
  amount_captured     BIGINT NOT NULL DEFAULT 0,
  amount_refunded     BIGINT NOT NULL DEFAULT 0,
  auth_expires_at     TIMESTAMPTZ,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_pay_amounts_nonneg
    CHECK (amount_requested >= 0 AND amount_authorised >= 0
           AND amount_captured >= 0 AND amount_refunded >= 0),
  CONSTRAINT chk_pay_capture_le_auth   CHECK (amount_captured <= amount_authorised),
  CONSTRAINT chk_pay_refund_le_capture CHECK (amount_refunded <= amount_captured)
);
CREATE INDEX idx_payment_booking  ON payment (booking_id);
CREATE INDEX idx_payment_merchant ON payment (merchant_reference);
CREATE INDEX idx_payment_psp      ON payment (psp_reference);
CREATE INDEX idx_payment_status   ON payment (status);

-- SCH-040
CREATE TABLE refund (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_id          UUID NOT NULL REFERENCES payment(id),
  amount              BIGINT NOT NULL,
  currency            CHAR(3) NOT NULL DEFAULT 'GBP',
  status              refund_status NOT NULL DEFAULT 'PENDING',
  merchant_reference  TEXT NOT NULL UNIQUE,
  psp_reference       TEXT UNIQUE,
  original_reference  TEXT NOT NULL,
  reason              TEXT,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_refund_amount_pos CHECK (amount > 0)
);
CREATE INDEX idx_refund_payment ON refund (payment_id);

-- SCH-050
CREATE TABLE ledger_posting (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  posting_type        posting_type NOT NULL,
  booking_id          UUID NOT NULL REFERENCES booking(id),
  booking_line_id     UUID REFERENCES booking_line(id),
  payment_id          UUID REFERENCES payment(id),
  refund_id           UUID REFERENCES refund(id),
  vertical            vertical NOT NULL,
  amount              BIGINT NOT NULL,
  currency            CHAR(3) NOT NULL DEFAULT 'GBP',
  psp_reference       TEXT,
  merchant_reference  TEXT,
  narration           TEXT,
  posted_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_posting_sign
    CHECK ( (posting_type = 'REVENUE'         AND amount >= 0)
         OR (posting_type = 'REFUND_REVERSAL' AND amount <= 0) )
);
CREATE INDEX idx_posting_vertical ON ledger_posting (vertical);
CREATE INDEX idx_posting_booking  ON ledger_posting (booking_id);
CREATE INDEX idx_posting_posted   ON ledger_posting (posted_at);

-- SCH-060
CREATE TABLE outbox_event (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_type      TEXT NOT NULL,
  aggregate_type  TEXT NOT NULL,
  aggregate_id    UUID NOT NULL,
  payload         JSONB NOT NULL,
  status          outbox_status NOT NULL DEFAULT 'PENDING',
  attempts        INT NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at    TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox_event (status, created_at) WHERE status = 'PENDING';

-- SCH-070
CREATE TABLE webhook_inbox (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  idempotency_key     TEXT NOT NULL UNIQUE,
  event_code          psp_event_code NOT NULL,
  merchant_reference  TEXT NOT NULL,
  psp_reference       TEXT,
  raw_payload         JSONB NOT NULL,
  received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at        TIMESTAMPTZ
);
CREATE INDEX idx_webhook_merchant ON webhook_inbox (merchant_reference);
