-- V7__drop_booking_amount_authorised.sql  [RX-004]
-- Removes the stored booking-level authorised roll-up (added by V3__payment_line_scoping.sql).
--
-- The Stage-4 "D3" folio roll-up (booking.amount_authorised) was a STORED derivation summing
-- EVERY payment's amount_authorised, including spent IMMEDIATE auths that had already captured —
-- which inflated the folio "secured" figure. It gated nothing (no capture guard, no completion
-- precondition reads it; the capture guard reads the PER-PAYMENT payment.amount_authorised).
--
-- It is replaced by a derive-on-read LIVE HOLD computed in DtoMapper folio assembly:
--   sum(payment.amount_authorised) WHERE payment.status = 'AUTHORISED'
-- Dropping the column removes the transient inflated value that was persisted between an
-- IMMEDIATE payment's AUTHORISATION and CAPTURE webhooks.
--
-- Forward migration only. V3 (frozen history) is NOT edited. The V6 booking_balance view reads
-- total_amount / amount_paid / amount_refunded only — it does NOT reference amount_authorised —
-- so no view rebuild is required.

ALTER TABLE booking DROP COLUMN amount_authorised;
