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
import com.hotelops.core.product.ProductFnb;
import com.hotelops.core.product.ProductRepository;
import com.hotelops.core.product.ProductSpa;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Overlap edge cases and pricing for {@link FnbStrategy}. Mirrors {@link SpaStrategyTest}.
 *
 * FnbStrategy is a @Component, which a @DataJpaTest slice does not load, so it is
 * instantiated directly over the autowired repositories (its only collaborator).
 *
 * The committed-overlap predicate (reused from ProductRepository.countCommittedQuantity)
 * is: starts_at < :endsAt AND ends_at > :startsAt — a half-open window, so adjacency
 * (ends_at == next starts_at) is NOT an overlap.
 */
class FnbStrategyTest extends AbstractDataJpaTest {

    @Autowired ProductRepository productRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired BookingLineRepository lineRepository;
    @Autowired CustomerRepository customerRepository;

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-07-01T19:00:00Z");
    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-07-01T21:00:00Z");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-07-01T23:00:00Z");

    @Test
    void no_existing_lines_yields_full_covers_capacity() {
        FnbStrategy strategy = new FnbStrategy(productRepository);
        ProductFnb fnb = saveFnb(40);

        assertThat(strategy.availableCapacity(fnb.getId(), T0, T1)).isEqualTo(40);
    }

    @Test
    void fully_overlapping_active_line_reduces_availability_by_its_quantity() {
        FnbStrategy strategy = new FnbStrategy(productRepository);
        ProductFnb fnb = saveFnb(40);
        saveLine(fnb, T0, T1, 4, BookingLineStatus.ACTIVE);

        // Same window: 40 - 4 = 36 left.
        assertThat(strategy.availableCapacity(fnb.getId(), T0, T1)).isEqualTo(36);
    }

    @Test
    void adjacent_non_overlapping_window_does_not_reduce_availability() {
        FnbStrategy strategy = new FnbStrategy(productRepository);
        ProductFnb fnb = saveFnb(40);
        // Existing line ends exactly when the queried window starts (ends_at == starts_at).
        saveLine(fnb, T0, T1, 4, BookingLineStatus.ACTIVE);

        assertThat(strategy.availableCapacity(fnb.getId(), T1, T2)).isEqualTo(40);
    }

    @Test
    void cancelled_line_does_not_reduce_availability() {
        FnbStrategy strategy = new FnbStrategy(productRepository);
        ProductFnb fnb = saveFnb(40);
        saveLine(fnb, T0, T1, 4, BookingLineStatus.CANCELLED);

        assertThat(strategy.availableCapacity(fnb.getId(), T0, T1)).isEqualTo(40);
    }

    @Test
    void unit_price_is_product_base_price_and_capture_mode_is_immediate() {
        FnbStrategy strategy = new FnbStrategy(productRepository);
        ProductFnb fnb = saveFnb(40);   // base price 4500

        assertThat(strategy.calculateUnitPrice(fnb.getId(), 1, T0, T1)).isEqualTo(4500L);
        assertThat(strategy.defaultCaptureMode()).isEqualTo(CaptureMode.IMMEDIATE);
        assertThat(strategy.vertical()).isEqualTo(Vertical.FNB);
    }

    @Test
    void line_amount_is_base_times_quantity_with_no_nights_factor() {
        FnbStrategy strategy = new FnbStrategy(productRepository);
        ProductFnb fnb = saveFnb(40);   // base price 4500

        // Even across a multi-day window, F&B never multiplies by nights — duration
        // pricing is a Rooms concern (KNOWN_LIMITATION_ROOM_PRICING.md).
        OffsetDateTime multiDayStart = OffsetDateTime.parse("2026-07-01T19:00:00Z");
        OffsetDateTime multiDayEnd   = OffsetDateTime.parse("2026-07-03T19:00:00Z");

        // 4500 × 2 = 9000, NOT × 2 nights.
        assertThat(strategy.calculateLineAmount(fnb.getId(), 2, multiDayStart, multiDayEnd))
                .isEqualTo(9000L);
    }

    @Test
    void non_fnb_product_is_rejected() {
        FnbStrategy strategy = new FnbStrategy(productRepository);
        ProductSpa spa = saveSpa();

        assertThatThrownBy(() -> strategy.calculateUnitPrice(spa.getId(), 1, T0, T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an FNB");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ProductFnb saveFnb(int coversCapacity) {
        ProductFnb f = new ProductFnb();
        f.setName("Dinner Service");
        f.setBasePrice(4500L);
        f.setCurrency("GBP");
        f.setServicePeriod("DINNER");
        f.setCoversCapacity(coversCapacity);
        f.setSeatingMinutes(120);
        return productRepository.save(f);
    }

    private ProductSpa saveSpa() {
        ProductSpa s = new ProductSpa();
        s.setName("60-Minute Massage");
        s.setBasePrice(5000L);
        s.setCurrency("GBP");
        s.setTreatmentKind("MASSAGE_60");
        s.setDurationMinutes(60);
        s.setConcurrentSlots(3);
        return productRepository.save(s);
    }

    private void saveLine(ProductFnb fnb, OffsetDateTime startsAt, OffsetDateTime endsAt,
                          int quantity, BookingLineStatus status) {
        BookingLine line = new BookingLine();
        line.setBooking(savedBooking());
        line.setProduct(fnb);
        line.setVertical(Vertical.FNB);
        line.setStatus(status);
        line.setStartsAt(startsAt);
        line.setEndsAt(endsAt);
        line.setQuantity(quantity);
        line.setUnitPrice(fnb.getBasePrice());
        line.setLineAmount(fnb.getBasePrice() * quantity);
        line.setCurrency("GBP");
        lineRepository.save(line);
    }

    private int bookingSeq = 0;

    private Booking savedBooking() {
        Customer c = new Customer();
        try {
            var f = Customer.class.getDeclaredField("shopperReference");
            f.setAccessible(true);
            f.set(c, String.format("SHPR-fnbstrat%05d", ++bookingSeq));
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
