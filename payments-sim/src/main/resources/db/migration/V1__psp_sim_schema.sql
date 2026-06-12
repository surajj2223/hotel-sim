-- payments-sim internal schema (WAVE0_05 §4) — PSP-009, PSP-010, PSP-011.
-- DDL copied verbatim from contracts/WAVE0_05_PSP_API.md §4.1, §4.2, §4.3.
-- Runs against the separate payments-sim-db instance (SCF-005, PSP-012);
-- NEVER co-mingled with core-api's V1__wave0_schema.sql.

-- §4.1 psp_payment (PSP-009)
CREATE TYPE psp_payment_status AS ENUM (              -- mirrors ENM-005 PaymentStatus subset
  'PENDING', 'AUTHORISED', 'CAPTURED', 'CAPTURE_FAILED',
  'CANCELLED', 'AUTH_EXPIRED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'FAILED'
);

CREATE TABLE psp_payment (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  merchant_reference  TEXT NOT NULL UNIQUE,           -- core-api-minted; PSP stores, never invents
  shopper_reference   TEXT NOT NULL,
  payment_link_id     TEXT NOT NULL UNIQUE,           -- PSP-minted at PSP-001
  psp_reference       TEXT UNIQUE,                    -- PSP-minted at AUTHORISATION (PSP-013)
  amount_requested    BIGINT NOT NULL CHECK (amount_requested  >  0),
  amount_authorised   BIGINT NOT NULL DEFAULT 0 CHECK (amount_authorised  >= 0),
  amount_captured     BIGINT NOT NULL DEFAULT 0 CHECK (amount_captured    >= 0),
  amount_refunded     BIGINT NOT NULL DEFAULT 0 CHECK (amount_refunded    >= 0),
  currency            CHAR(3) NOT NULL,
  status              psp_payment_status NOT NULL DEFAULT 'PENDING',
  capture_mode        TEXT NOT NULL,                  -- mirrors ENM-004 CaptureMode
  callback_url        TEXT NOT NULL,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_captured_le_authorised CHECK (amount_captured <= amount_authorised),
  CONSTRAINT chk_refunded_le_captured   CHECK (amount_refunded <= amount_captured)
);

CREATE INDEX idx_psp_payment_psp_reference ON psp_payment (psp_reference);

-- §4.2 psp_refund (PSP-010)
CREATE TYPE psp_refund_status AS ENUM (               -- mirrors ENM-006 RefundStatus
  'PENDING', 'REFUNDED', 'REFUND_FAILED'
);

CREATE TABLE psp_refund (
  id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  refund_merchant_reference   TEXT NOT NULL UNIQUE,    -- core-api-minted
  psp_reference               TEXT NOT NULL UNIQUE,    -- PSP-minted; distinct from parent's
  original_reference          TEXT NOT NULL REFERENCES psp_payment (psp_reference),
  amount                      BIGINT NOT NULL CHECK (amount > 0),
  currency                    CHAR(3) NOT NULL,
  status                      psp_refund_status NOT NULL DEFAULT 'PENDING',
  reason                      TEXT,
  created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_psp_refund_original_reference ON psp_refund (original_reference);

-- §4.3 psp_event_sequence (PSP-011)
CREATE TABLE psp_event_sequence (
  psp_reference  TEXT NOT NULL,
  event_code     TEXT NOT NULL,                       -- mirrors ENM-007 PspEventCode
  seq            INT  NOT NULL DEFAULT 1,
  last_emitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (psp_reference, event_code)
);
