-- V6__balance_split.sql  [RX-003]
-- Splits the overloaded SCH-021 booking_balance.balance (= total - paid + refunded)
-- into two honestly-named, separately-derived columns:
--   customer_owes = GREATEST(0, total_amount - amount_paid)   -- settlement predicate
--   net_revenue   = amount_paid - amount_refunded             -- finance read
-- Read-model only. No capture/refund/posting behaviour changes.
-- The column set changes, so the view is dropped and recreated (CREATE OR REPLACE
-- cannot change the column list). V1__wave0_schema.sql is frozen and untouched.

DROP VIEW booking_balance;

CREATE VIEW booking_balance AS
  SELECT id AS booking_id,
         total_amount,
         amount_paid,
         amount_refunded,
         GREATEST(0, total_amount - amount_paid) AS customer_owes,
         (amount_paid - amount_refunded)         AS net_revenue
  FROM booking;
