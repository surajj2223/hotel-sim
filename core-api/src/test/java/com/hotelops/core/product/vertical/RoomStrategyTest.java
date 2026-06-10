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
