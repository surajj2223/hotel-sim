package com.hotelops.core.booking;

import com.hotelops.core.AbstractDataJpaTest;
import com.hotelops.core.common.enums.BookingLineStatus;
import com.hotelops.core.common.enums.BookingStatus;
import com.hotelops.core.common.enums.Vertical;
import com.hotelops.core.customer.Customer;
import com.hotelops.core.customer.CustomerRepository;
import com.hotelops.core.product.ProductRoom;
import com.hotelops.core.product.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests proving SCH-020, SCH-021, SCH-022.
 */
class BookingEntityTest extends AbstractDataJpaTest {

    @Autowired BookingRepository bookingRepository;
    @Autowired BookingLineRepository lineRepository;
    @Autowired BookingBalanceRepository balanceRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired ProductRepository productRepository;

    // ── SCH-020 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_020_booking_persists_with_default_amounts() {
        Booking b = bookingRepository.save(booking());
        assertThat(b.getId()).isNotNull();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(b.getTotalAmount()).isZero();
        assertThat(b.getAmountPaid()).isZero();
        assertThat(b.getAmountRefunded()).isZero();
    }

    @Test
    void SCH_020_booking_amounts_nonneg_check_rejects_negative_total() {
        Booking b = booking();
        b.setTotalAmount(-1L);
        assertThatThrownBy(() -> bookingRepository.saveAndFlush(b))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void SCH_020_booking_amounts_nonneg_check_rejects_negative_paid() {
        Booking b = booking();
        b.setAmountPaid(-1L);
        assertThatThrownBy(() -> bookingRepository.saveAndFlush(b))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── SCH-021 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_021_booking_balance_view_returns_correct_derived_balance() {
        Booking b = bookingRepository.save(booking());
        b.setTotalAmount(50000L);
        b.setAmountPaid(20000L);
        b.setAmountRefunded(5000L);
        bookingRepository.save(b);

        Optional<BookingBalance> balOpt = balanceRepository.findByBookingId(b.getId());
        assertThat(balOpt).isPresent();
        BookingBalance bal = balOpt.get();
        // balance = 50000 - 20000 + 5000 = 35000
        assertThat(bal.getBalance()).isEqualTo(35000L);
        assertThat(bal.isPaid()).isFalse();
    }

    @Test
    void SCH_021_booking_balance_is_zero_when_fully_paid() {
        Booking b = bookingRepository.save(booking());
        b.setTotalAmount(30000L);
        b.setAmountPaid(30000L);
        b.setAmountRefunded(0L);
        bookingRepository.save(b);

        BookingBalance bal = balanceRepository.findByBookingId(b.getId()).orElseThrow();
        assertThat(bal.getBalance()).isZero();
        assertThat(bal.isPaid()).isTrue();
    }

    // ── SCH-022 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_022_booking_line_persists_with_price_snapshot() {
        Booking b = bookingRepository.save(booking());
        BookingLine line = buildLine(b, 10000L, 3);   // £100 * 3 nights = £300
        BookingLine saved = lineRepository.save(line);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getLineAmount()).isEqualTo(30000L);
        assertThat(saved.getVertical()).isEqualTo(Vertical.ROOM);
        assertThat(saved.getStatus()).isEqualTo(BookingLineStatus.ACTIVE);
    }

    @Test
    void SCH_022_line_amount_check_rejects_below_floor_amount() {
        // RX-002: chk_line_amount relaxed to (line_amount > 0 AND line_amount >=
        // unit_price * quantity) — line_amount is strategy-owned (rooms multiply by nights),
        // so an amount ABOVE the floor is now valid. A value BELOW the no-under-count floor
        // (here 9999 < 5000*2) must still be rejected.
        Booking b = bookingRepository.save(booking());
        ProductRoom product = productRepository.save(room());
        BookingLine line = new BookingLine();
        line.setBooking(b);
        line.setProduct(product);
        line.setVertical(Vertical.ROOM);
        line.setStartsAt(OffsetDateTime.now());
        line.setEndsAt(OffsetDateTime.now().plusDays(1));
        line.setQuantity(2);
        line.setUnitPrice(5000L);
        line.setLineAmount(9999L);   // below the floor unit_price*quantity = 10000
        assertThatThrownBy(() -> lineRepository.saveAndFlush(line))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void SCH_022_line_window_check_rejects_ends_before_starts() {
        Booking b = bookingRepository.save(booking());
        ProductRoom product = productRepository.save(room());
        BookingLine line = new BookingLine();
        line.setBooking(b);
        line.setProduct(product);
        line.setVertical(Vertical.ROOM);
        line.setStartsAt(OffsetDateTime.now().plusDays(2));
        line.setEndsAt(OffsetDateTime.now());   // ends before starts
        line.setQuantity(1);
        line.setUnitPrice(5000L);
        line.setLineAmount(5000L);
        assertThatThrownBy(() -> lineRepository.saveAndFlush(line))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void SCH_022_line_quantity_must_be_positive() {
        Booking b = bookingRepository.save(booking());
        ProductRoom product = productRepository.save(room());
        BookingLine line = new BookingLine();
        line.setBooking(b);
        line.setProduct(product);
        line.setVertical(Vertical.ROOM);
        line.setStartsAt(OffsetDateTime.now());
        line.setEndsAt(OffsetDateTime.now().plusDays(1));
        line.setQuantity(0);   // invalid
        line.setUnitPrice(5000L);
        line.setLineAmount(0L);
        assertThatThrownBy(() -> lineRepository.saveAndFlush(line))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Booking booking() {
        Customer c = new Customer();
        try {
            var f = Customer.class.getDeclaredField("shopperReference");
            f.setAccessible(true);
            f.set(c, "SHPR-booktest00001");
        } catch (Exception e) { throw new RuntimeException(e); }
        c.setFullName("Test Guest");
        c = customerRepository.save(c);
        Booking b = new Booking();
        b.setCustomer(c);
        return b;
    }

    private BookingLine buildLine(Booking b, long unitPrice, int qty) {
        ProductRoom product = productRepository.save(room());
        BookingLine line = new BookingLine();
        line.setBooking(b);
        line.setProduct(product);
        line.setVertical(Vertical.ROOM);
        line.setStartsAt(OffsetDateTime.now());
        line.setEndsAt(OffsetDateTime.now().plusDays(qty));
        line.setQuantity(qty);
        line.setUnitPrice(unitPrice);
        line.setLineAmount(unitPrice * qty);
        return line;
    }

    private ProductRoom room() {
        ProductRoom r = new ProductRoom();
        r.setName("Standard Room");
        r.setBasePrice(10000L);
        r.setCurrency("GBP");
        r.setRoomCount(10);
        return r;
    }
}
