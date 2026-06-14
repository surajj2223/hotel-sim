-- V3: scoped payment→line coverage + live folio authorised roll-up [WHK-016; Stage 4 Slice 1]
--
-- ADDITIVE ONLY. No ALTER that rewrites the semantics of payment / booking_line /
-- ledger_posting (SCH-030 / SCH-022 / SCH-050). One new table plus one additive column.

-- WHK-016 — payment_line: many-to-many association carrying a per-line covered amount.
-- A payment MAY cover a set of booking lines, each with its own amount. When present,
-- LedgerService allocates the captured/refunded amount across exactly these lines instead
-- of the WHK-012 fill-by-line-order fallback. Absent rows ⇒ fallback is unchanged.
CREATE TABLE payment_line (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_id       UUID NOT NULL REFERENCES payment(id) ON DELETE CASCADE,
  booking_line_id  UUID NOT NULL REFERENCES booking_line(id),
  amount           BIGINT NOT NULL,
  currency         CHAR(3) NOT NULL DEFAULT 'GBP',
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_payment_line_amount_pos CHECK (amount > 0),
  CONSTRAINT uq_payment_line UNIQUE (payment_id, booking_line_id)
);
CREATE INDEX idx_payment_line_payment ON payment_line (payment_id);

-- D3 — live folio authorised roll-up. Maintained alongside total/paid/refunded in
-- BookingService.recalculateTotals (INV-004 register). Visible number only — no
-- enforcement, no checkout-blocking, no incremental-auth.
ALTER TABLE booking ADD COLUMN amount_authorised BIGINT NOT NULL DEFAULT 0;
