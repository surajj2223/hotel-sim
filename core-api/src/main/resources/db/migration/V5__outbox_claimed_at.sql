-- V5: outbox claim timestamp for stale-PROCESSING reclaim [SCH-061]
--
-- Flag-2 crash recovery. A row claimed PENDING -> PROCESSING whose handler never commits
-- (JVM crash between the claim commit and the handler commit) is invisible to the PENDING
-- poll, so its ledger posting silently never lands. The reclaim pass needs the CLAIM TIME
-- to find rows that have been PROCESSING longer than a cutoff. V2 added the PROCESSING
-- state but no timestamp; this adds it.
--
-- Additive + nullable: rows that predate this migration (incl. any in-flight PROCESSING
-- row) get claimed_at = NULL and are therefore NOT reclaimed (claimed_at < cutoff never
-- matches NULL). Accepted for the POC (no persistent stuck rows). No existing column changes.
ALTER TABLE outbox_event ADD COLUMN claimed_at TIMESTAMPTZ;

-- Supports the reclaim candidate query (PROCESSING rows older than a cutoff), mirroring the
-- partial idx_outbox_pending index that supports the normal PENDING poll.
CREATE INDEX idx_outbox_reclaim ON outbox_event (status, claimed_at) WHERE status = 'PROCESSING';
