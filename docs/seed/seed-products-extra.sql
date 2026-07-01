-- ============================================================================
-- seed-products-extra.sql  (OPTIONAL, ADDITIVE)
-- ----------------------------------------------------------------------------
-- Extends the base seed-products.sql with four more bookable SKUs so the seeded
-- demo data draws from a richer catalogue. Purely additive and idempotent
-- (ON CONFLICT DO NOTHING) — safe to re-run, and safe to skip entirely:
-- seed.mjs probes availability at startup and only uses products that exist.
--
-- Mirrors the base file's shape EXACTLY (verified against V1__wave0_schema.sql):
-- each product is one row in `product` (identity + base_price) PLUS one row in
-- its vertical companion table (the capacity model that makes it bookable).
-- A product row without its companion row is NOT bookable — availability is
-- computed from the companion tables.
--
-- Apply against the core-api database:
--   docker compose exec -T db psql -U hotelops -d hotelops < seed-products-extra.sql
-- ============================================================================

-- ROOM: Executive Suite — £320.00/night (32000 pence), 2 rooms, high floor, king
INSERT INTO product (id, vertical, name, active, base_price, currency)
VALUES ('66666666-6666-6666-6666-666666666666', 'ROOM', 'Executive Suite', TRUE, 32000, 'GBP')
ON CONFLICT (id) DO NOTHING;

INSERT INTO product_room (product_id, floor_band, bed_type, max_occupancy, quiet, room_count)
VALUES ('66666666-6666-6666-6666-666666666666', 'HIGH', 'KING', 3, TRUE, 2)
ON CONFLICT (product_id) DO NOTHING;

-- SPA: Sauna Session — £40.00 (4000 pence), 30 min, 6 concurrent slots, no gender pref
INSERT INTO product (id, vertical, name, active, base_price, currency)
VALUES ('77777777-7777-7777-7777-777777777777', 'SPA', 'Sauna Session', TRUE, 4000, 'GBP')
ON CONFLICT (id) DO NOTHING;

INSERT INTO product_spa (product_id, treatment_kind, duration_minutes, therapist_gender, concurrent_slots)
VALUES ('77777777-7777-7777-7777-777777777777', 'SAUNA_30', 30, NULL, 6)
ON CONFLICT (product_id) DO NOTHING;

-- FNB: Weekend Brunch — £35.00 (3500 pence), BRUNCH period, 30 covers, 90-min seating
INSERT INTO product (id, vertical, name, active, base_price, currency)
VALUES ('88888888-8888-8888-8888-888888888888', 'FNB', 'Weekend Brunch', TRUE, 3500, 'GBP')
ON CONFLICT (id) DO NOTHING;

INSERT INTO product_fnb (product_id, service_period, covers_capacity, seating_minutes)
VALUES ('88888888-8888-8888-8888-888888888888', 'BRUNCH', 30, 90)
ON CONFLICT (product_id) DO NOTHING;

-- FNB: Set Lunch — £28.00 (2800 pence), LUNCH period, 35 covers, 75-min seating
INSERT INTO product (id, vertical, name, active, base_price, currency)
VALUES ('99999999-9999-9999-9999-999999999999', 'FNB', 'Set Lunch', TRUE, 2800, 'GBP')
ON CONFLICT (id) DO NOTHING;

INSERT INTO product_fnb (product_id, service_period, covers_capacity, seating_minutes)
VALUES ('99999999-9999-9999-9999-999999999999', 'LUNCH', 35, 75)
ON CONFLICT (product_id) DO NOTHING;

SELECT id, vertical, name, base_price, currency FROM product ORDER BY name;