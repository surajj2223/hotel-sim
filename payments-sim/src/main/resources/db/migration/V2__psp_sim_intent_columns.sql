-- Carry capture/cancel intent on psp_payment between PSP-002/003 request side (1B)
-- and the dispatcher (1C). PSP-009 columns alone cannot represent "queued but not yet
-- emitted" — 1B persists intent here; 1C drains it via the webhook dispatcher.

ALTER TABLE psp_payment
  ADD COLUMN pending_capture_amount BIGINT      NULL
    CHECK (pending_capture_amount IS NULL OR pending_capture_amount > 0),
  ADD COLUMN cancellation_pending   BOOLEAN  NOT NULL DEFAULT FALSE;
