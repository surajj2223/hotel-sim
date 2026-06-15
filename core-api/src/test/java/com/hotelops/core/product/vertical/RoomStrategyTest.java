package com.hotelops.core.product.vertical;

import com.hotelops.core.AbstractDataJpaTest;
import com.hotelops.core.booking.Booking;
import com.hotelops.core.booking.BookingLine;
import com.hotelops.core.booking.BookingLineRepository;
import com.hotelops.core.booking.BookingRepository;
import com.hotelops.core.common.enums.BookingLineStatus;
import com.hotelops.core.common.enums.CaptureMode;
import com.hotelops.core.common.enums.Vertical;
import com.hotelops.core.customer.Customer;
import com.hotelops.core.customer.CustomerRepository;
import com.hotelops.core.product.ProductRepository;
import com.hotelops.core.product.ProductRoom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Overlap edge cases for {@link RoomStrategy#availableCapacity}.
 *
 * RoomStrategy is a @Component, which a @DataJpaTest slice does not load, so it is
 * instantiated directly over the autowired repositories (its only collaborator).
 *
 * The committed-overlap predicate (reused from ProductRepository.countCommittedQuantity)
 * is: starts_at < :endsAt AND ends_at > :startsAt — a half-open window, so adjacency
 * (ends_at == next starts_at) is NOT an overlap.
 */
class RoomStrategyTest extends AbstractDataJpaTest {

    @Autowired ProductRepository productRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired BookingLineRepository lineRepository;
    @Autowired CustomerRepository customerRepository;

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-07-01T14:00:00Z");
    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-07-02T11:00:00Z");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-07-03T11:00:00Z");

    @Test
    void no_existing_lines_yields_full_room_count() {
        RoomStrategy strategy = new RoomStrategy(productRepository);
        ProductRoom room = saveRoom(5);

        assertThat(strategy.availableCapacity(room.getId(), T0, T1)).isEqualTo(5);
    }

    @Test
    void fully_overlapping_active_line_reduces_availability_by_its_quantity() {
        RoomStrategy strategy = new RoomStrategy(productRepository);
        ProductRoom room = saveRoom(5);
        saveLine(room, T0, T1, 2, BookingLineStatus.ACTIVE);

        // Same window: 5 - 2 = 3 left.
        assertThat(strategy.availableCapacity(room.getId(), T0, T1)).isEqualTo(3);
    }

    @Test
    void adjacent_non_overlapping_window_does_not_reduce_availability() {
        RoomStrategy strategy = new RoomStrategy(productRepository);
        ProductRoom room = saveRoom(5);
        // Existing line ends exactly when the queried window starts (ends_at == starts_at).
        saveLine(room, T0, T1, 2, BookingLineStatus.ACTIVE);

        assertThat(strategy.availableCapacity(room.getId(), T1, T2)).isEqualTo(5);
    }

    @Test
    void cancelled_line_does_not_reduce_availability() {
        RoomStrategy strategy = new RoomStrategy(productRepository);
        ProductRoom room = saveRoom(5);
        saveLine(room, T0, T1, 2, BookingLineStatus.CANCELLED);

        assertThat(strategy.availableCapacity(room.getId(), T0, T1)).isEqualTo(5);
    }

    @Test
    void unit_price_is_product_base_price_and_capture_mode_is_manual() {
        RoomStrategy strategy = new RoomStrategy(productRepository);
        ProductRoom room = saveRoom(5);   // base price 10000

        assertThat(strategy.calculateUnitPrice(room.getId(), 1, T0, T1)).isEqualTo(10000L);
        assertThat(strategy.defaultCaptureMode()).isEqualTo(CaptureMode.MANUAL);
        assertThat(strategy.vertical()).isEqualTo(Vertical.ROOM);
    }

    // ── line amount = rate × rooms × nights (KNOWN_LIMITATION_ROOM_PRICING.md) ────

    // Calendar-date nights: 15:00 → 11:00 across Jul 1–4 is 3 nights, check-in/out
    // times do not distort the count.
    private static final OffsetDateTime CHECK_IN  = OffsetDateTime.parse("2026-07-01T15:00:00Z");
    private static final OffsetDateTime CHECK_OUT = OffsetDateTime.parse("2026-07-04T11:00:00Z");

    @Test
    void line_amount_multiplies_unit_price_by_nights() {
        RoomStrategy strategy = new RoomStrategy(productRepository);
        ProductRoom room = saveRoom(5);   // base price 10000

        // 10000 × 1 room × 3 nights = 30000
        assertThat(strategy.calculateLineAmount(room.getId(), 1, CHECK_IN, CHECK_OUT))
                .isEqualTo(30000L);
    }

    @Test
    void line_amount_multiplies_by_rooms_and_nights() {
        RoomStrategy strategy = new RoomStrategy(productRepository);
        ProductRoom room = saveRoom(5);   // base price 10000

        // 10000 × 2 rooms × 3 nights = 60000
        assertThat(strategy.calculateLineAmount(room.getId(), 2, CHECK_IN, CHECK_OUT))
                .isEqualTo(60000L);
    }

    @Test
    void line_amount_for_single_night_equals_rate_times_quantity() {
        RoomStrategy strategy = new RoomStrategy(productRepository);
        ProductRoom room = saveRoom(5);   // base price 10000

        // T0 → T1 is Jul 1 → Jul 2 = 1 night, so the ×1 path stays at rate × quantity.
        assertThat(strategy.calculateLineAmount(room.getId(), 1, T0, T1)).isEqualTo(10000L);
    }

    @Test
    void zero_night_line_is_rejected() {
        RoomStrategy strategy = new RoomStrategy(productRepository);
        ProductRoom room = saveRoom(5);

        // Same calendar date (day-use) → 0 nights → loud rejection, no silent £0 room.
        OffsetDateTime sameDayIn  = OffsetDateTime.parse("2026-07-01T09:00:00Z");
        OffsetDateTime sameDayOut = OffsetDateTime.parse("2026-07-01T17:00:00Z");

        assertThatThrownBy(() -> strategy.calculateLineAmount(room.getId(), 1, sameDayIn, sameDayOut))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one night");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ProductRoom saveRoom(int roomCount) {
        ProductRoom r = new ProductRoom();
        r.setName("Standard Room");
        r.setBasePrice(10000L);
        r.setCurrency("GBP");
        r.setRoomCount(roomCount);
        return productRepository.save(r);
    }

    private void saveLine(ProductRoom room, OffsetDateTime startsAt, OffsetDateTime endsAt,
                          int quantity, BookingLineStatus status) {
        BookingLine line = new BookingLine();
        line.setBooking(savedBooking());
        line.setProduct(room);
        line.setVertical(Vertical.ROOM);
        line.setStatus(status);
        line.setStartsAt(startsAt);
        line.setEndsAt(endsAt);
        line.setQuantity(quantity);
        line.setUnitPrice(room.getBasePrice());
        line.setLineAmount(room.getBasePrice() * quantity);
        line.setCurrency("GBP");
        lineRepository.save(line);
    }

    private int bookingSeq = 0;

    private Booking savedBooking() {
        Customer c = new Customer();
        try {
            var f = Customer.class.getDeclaredField("shopperReference");
            f.setAccessible(true);
            f.set(c, String.format("SHPR-roomstrat%05d", ++bookingSeq));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        c.setFullName("Test Guest");
        c = customerRepository.save(c);
        Booking b = new Booking();
        b.setCustomer(c);
        return bookingRepository.save(b);
    }
}
