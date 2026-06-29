-- ============================================================================
-- hotel-sim dev seed — products for manual API testing (Postman collection)
-- Idempotent: safe to re-run. Fixed UUIDs so the Postman collection's
-- {{productId}} default works out of the box.
--
-- Run against the compose stack:
--   docker compose exec -T db psql -U hotelops -d hotelops < seed-products.sql
-- ============================================================================

-- ROOM: Deluxe King — £180.00/night (18000 pence), 2 physical rooms
INSERT INTO product (id, vertical, name, active, base_price, currency)
VALUES ('11111111-1111-1111-1111-111111111111', 'ROOM', 'Deluxe King', TRUE, 18000, 'GBP')
ON CONFLICT (id) DO NOTHING;

INSERT INTO
	product_room (product_id, floor_band, bed_type, max_occupancy, quiet, room_count)
VALUES
	('11111111-1111-1111-1111-111111111111', 'HIGH', 'KING', 2, TRUE, 2)
ON CONFLICT (product_id) DO NOTHING;

-- ROOM: Standard Twin — £95.00/night, 5 rooms (for contrast in availability results)
INSERT INTO product (id, vertical, name, active, base_price, currency)
VALUES ('22222222-2222-2222-2222-222222222222', 'ROOM', 'Standard Twin', TRUE, 9500, 'GBP')
ON CONFLICT (id) DO NOTHING;

INSERT INTO product_room (product_id, floor_band, bed_type, max_occupancy, quiet, room_count)
VALUES ('22222222-2222-2222-2222-222222222222', 'LOW', 'TWIN', 2, FALSE, 5)
ON CONFLICT (product_id) DO NOTHING;

-- SPA: Couples Massage 60 — £80.00 (8000 pence), 3 concurrent slots, female therapist preference
INSERT INTO product (id, vertical, name, active, base_price, currency)
VALUES ('33333333-3333-3333-3333-333333333333', 'SPA', 'Couples Massage 60', TRUE, 8000, 'GBP')
ON CONFLICT (id) DO NOTHING;

INSERT INTO product_spa (product_id, treatment_kind, duration_minutes, therapist_gender, concurrent_slots)
VALUES ('33333333-3333-3333-3333-333333333333', 'MASSAGE_60', 60, 'FEMALE', 3)
ON CONFLICT (product_id) DO NOTHING;

-- SPA: Signature Facial 45 — £65.00 (6500 pence), 2 concurrent slots, no therapist-gender preference
INSERT INTO product (id, vertical, name, active, base_price, currency)
VALUES ('44444444-4444-4444-4444-444444444444', 'SPA', 'Signature Facial 45', TRUE, 6500, 'GBP')
ON CONFLICT (id) DO NOTHING;

INSERT INTO product_spa (product_id, treatment_kind, duration_minutes, therapist_gender, concurrent_slots)
VALUES ('44444444-4444-4444-4444-444444444444', 'FACIAL_45', 45, NULL, 2)
ON CONFLICT (product_id) DO NOTHING;

-- FNB: Dinner Service — £45.00 (4500 pence), DINNER service period, 40 covers, 120-min seating
INSERT INTO product (id, vertical, name, active, base_price, currency)
VALUES ('55555555-5555-5555-5555-555555555555', 'FNB', 'Dinner Service', TRUE, 4500, 'GBP')
ON CONFLICT (id) DO NOTHING;

INSERT INTO product_fnb (product_id, service_period, covers_capacity, seating_minutes)
VALUES ('55555555-5555-5555-5555-555555555555', 'DINNER', 40, 120)
ON CONFLICT (product_id) DO NOTHING;

SELECT id, vertical, name, base_price, currency FROM product ORDER BY name;
