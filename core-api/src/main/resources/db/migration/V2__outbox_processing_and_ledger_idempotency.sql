-- V2: outbox PROCESSING state + ledger idempotency backstop [WHK-013; GAP-2]

-- Add PROCESSING to the outbox_status Postgres ENUM.
-- The Java enum already carries this value; the DB type must agree.
ALTER TYPE outbox_status ADD VALUE IF NOT EXISTS 'PROCESSING';

-- Ledger idempotency backstop [WHK-007, WHK-012; GAP-1]:
-- one REVENUE posting per (payment, booking line).
CREATE UNIQUE INDEX uq_posting_capture_line
    ON ledger_posting(payment_id, booking_line_id)
    WHERE posting_type = 'REVENUE' AND booking_line_id IS NOT NULL;

-- Ledger idempotency backstop [WHK-009; GAP-1]:
-- one REFUND_REVERSAL posting per (refund, booking line).
CREATE UNIQUE INDEX uq_posting_refund_line
    ON ledger_posting(refund_id, booking_line_id)
    WHERE posting_type = 'REFUND_REVERSAL' AND booking_line_id IS NOT NULL;
