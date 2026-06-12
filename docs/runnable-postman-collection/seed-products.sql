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

SELECT id, vertical, name, base_price, currency FROM product ORDER BY name;
