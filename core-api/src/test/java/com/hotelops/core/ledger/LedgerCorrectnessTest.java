package com.hotelops.core.ledger;

import com.hotelops.core.booking.Booking;
import com.hotelops.core.booking.BookingLine;
import com.hotelops.core.common.enums.BookingLineStatus;
import com.hotelops.core.common.enums.PostingType;
import com.hotelops.core.common.enums.Vertical;
import com.hotelops.core.payment.Payment;
import com.hotelops.core.payment.PaymentRepository;
import com.hotelops.core.payment.Refund;
import com.hotelops.core.payment.RefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Unit tests proving the §5 worked examples from WAVE0_03 exactly.
 *
 * Folio: line R = Room £500 (50000 minor units, created first),
 *        line S = Spa  £200 (20000 minor units, created second).
 * Fill-by-line-order: Room is satisfied before Spa [WHK-007, WHK-009, WHK-012].
 */
@ExtendWith(MockitoExtension.class)
class LedgerCorrectnessTest {

    @Mock LedgerPostingRepository postingRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock RefundRepository refundRepository;

    LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(postingRepository, paymentRepository, refundRepository);
        when(postingRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Builds the two-line folio described in WAVE0_03 §5. */
    private Booking twoLineBooking() {
        Booking booking = new Booking();

        BookingLine room = new BookingLine();
        room.setVertical(Vertical.ROOM);
        room.setLineAmount(50000L);
        room.setStatus(BookingLineStatus.ACTIVE);
        room.setCreatedAt(OffsetDateTime.now().minusMinutes(10)); // created first
        room.setBooking(booking);

        BookingLine spa = new BookingLine();
        spa.setVertical(Vertical.SPA);
        spa.setLineAmount(20000L);
        spa.setStatus(BookingLineStatus.ACTIVE);
        spa.setCreatedAt(OffsetDateTime.now().minusMinutes(5));  // created second
        spa.setBooking(booking);

        booking.getLines().add(room);
        booking.getLines().add(spa);
        return booking;
    }

    /** Builds a payment AND stubs paymentRepository (use when calling postCapture). */
    private Payment paymentWith(Booking booking, long captureAmount) {
        Payment p = buildPayment(booking, captureAmount);
        when(paymentRepository.findById(p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    /** Builds a payment WITHOUT stubbing paymentRepository (use as refund parent only). */
    private Payment paymentParent(Booking booking, long captureAmount) {
        return buildPayment(booking, captureAmount);
    }

    private Payment buildPayment(Booking booking, long captureAmount) {
        Payment p = new Payment();
        setId(p, UUID.randomUUID());
        p.setBooking(booking);
        p.setAmountCaptured(captureAmount);
        p.setCurrency("GBP");
        p.setPspReference("PSP-TEST");
        p.setMerchantReference("MR-TEST");
        return p;
    }

    private Refund refundFor(Payment payment, long refundAmount) {
        Refund r = new Refund();
        UUID refundId = UUID.randomUUID();
        setId(r, refundId);
        r.setPayment(payment);
        r.setAmount(refundAmount);
        r.setCurrency("GBP");
        r.setPspReference("PSP-REFUND-TEST");
        r.setMerchantReference("MR-REFUND-TEST");
        r.setOriginalReference("PSP-TEST");
        when(refundRepository.findById(refundId)).thenReturn(Optional.of(r));
        return r;
    }

    private static void setId(Object entity, UUID id) {
        try {
            var f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── §5 example 1: Full capture 70000 ─────────────────────────────────────

    @Test
    void WHK012_fullCapture_70000_roomAndSpaFullyAllocated() {
        // WAVE0_03 §5: "Full capture 70000: R→50000 (REVENUE/ROOM), S→20000 (REVENUE/SPA). Sum 70000."
        Booking booking = twoLineBooking();
        Payment payment = paymentWith(booking, 70000L);

        List<LedgerPosting> postings = ledgerService.postCapture(payment.getId());

        assertThat(postings).hasSize(2);
        assertThat(postings.get(0).getVertical()).isEqualTo(Vertical.ROOM);
        assertThat(postings.get(0).getAmount()).isEqualTo(50000L);
        assertThat(postings.get(0).getPostingType()).isEqualTo(PostingType.REVENUE);
        assertThat(postings.get(1).getVertical()).isEqualTo(Vertical.SPA);
        assertThat(postings.get(1).getAmount()).isEqualTo(20000L);
        assertThat(postings.get(1).getPostingType()).isEqualTo(PostingType.REVENUE);
        assertThat(postings.stream().mapToLong(LedgerPosting::getAmount).sum()).isEqualTo(70000L);
    }

    // ── §5 example 2: Partial capture 54000 ──────────────────────────────────

    @Test
    void WHK012_partialCapture_54000_roomFilledSpaPartial() {
        // WAVE0_03 §5: "Partial capture 54000: R→50000 (REVENUE/ROOM), S→4000 (REVENUE/SPA). Sum 54000."
        Booking booking = twoLineBooking();
        Payment payment = paymentWith(booking, 54000L);

        List<LedgerPosting> postings = ledgerService.postCapture(payment.getId());

        assertThat(postings).hasSize(2);
        assertThat(postings.get(0).getVertical()).isEqualTo(Vertical.ROOM);
        assertThat(postings.get(0).getAmount()).isEqualTo(50000L);
        assertThat(postings.get(1).getVertical()).isEqualTo(Vertical.SPA);
        assertThat(postings.get(1).getAmount()).isEqualTo(4000L);
        assertThat(postings.stream().mapToLong(LedgerPosting::getAmount).sum()).isEqualTo(54000L);
    }

    // ── §5 example 3: Partial capture 30000 ──────────────────────────────────

    @Test
    void WHK012_partialCapture_30000_roomOnlyNoSpaPosting() {
        // WAVE0_03 §5: "Partial capture 30000: R→30000 (REVENUE/ROOM). S→0 (no posting). Sum 30000."
        Booking booking = twoLineBooking();
        Payment payment = paymentWith(booking, 30000L);

        List<LedgerPosting> postings = ledgerService.postCapture(payment.getId());

        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).getVertical()).isEqualTo(Vertical.ROOM);
        assertThat(postings.get(0).getAmount()).isEqualTo(30000L);
        assertThat(postings.stream().mapToLong(LedgerPosting::getAmount).sum()).isEqualTo(30000L);
    }

    // ── §5 example 4: Refund 6000 after the 54000 capture ────────────────────

    @Test
    void WHK012_refund_6000_after54000Capture_reversesFromRoomFirst() {
        // WAVE0_03 §5: "Refund 6000 after the 54000 capture: R→−6000 (REFUND_REVERSAL/ROOM).
        //               Net room revenue 44000; spa untouched."
        Booking booking = twoLineBooking();
        Payment payment = paymentParent(booking, 54000L);
        Refund refund = refundFor(payment, 6000L);

        List<LedgerPosting> postings = ledgerService.postRefund(refund.getId());

        // Room line absorbs the full 6000 (50000 line amount > 6000 remaining)
        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).getVertical()).isEqualTo(Vertical.ROOM);
        assertThat(postings.get(0).getAmount()).isEqualTo(-6000L);
        assertThat(postings.get(0).getPostingType()).isEqualTo(PostingType.REFUND_REVERSAL);
        assertThat(postings.stream().mapToLong(LedgerPosting::getAmount).sum()).isEqualTo(-6000L);
    }

    // ── Cross-cutting invariants ──────────────────────────────────────────────

    @Test
    void WHK007_allCapturePostingsCarryBookingLineId() {
        // Every per-line posting must reference its booking line (WHK-007).
        Booking booking = twoLineBooking();
        Payment payment = paymentWith(booking, 70000L);
        List<LedgerPosting> postings = ledgerService.postCapture(payment.getId());
        assertThat(postings).allMatch(p -> p.getBookingLine() != null);
    }

    @Test
    void WHK009_allRefundPostingsCarryBookingLineId() {
        Booking booking = twoLineBooking();
        Payment payment = paymentParent(booking, 70000L);
        Refund refund = refundFor(payment, 15000L);
        List<LedgerPosting> postings = ledgerService.postRefund(refund.getId());
        assertThat(postings).allMatch(p -> p.getBookingLine() != null);
    }

    @Test
    void WHK012_captureAllocationsAlwaysSumToEventAmount() {
        // Exactness: fill-by-order produces no rounding remainder (unlike pro-rata).
        for (long captureAmount : List.of(1L, 30000L, 50000L, 54000L, 70000L)) {
            Booking booking = twoLineBooking();
            Payment payment = paymentWith(booking, captureAmount);
            List<LedgerPosting> postings = ledgerService.postCapture(payment.getId());
            long sum = postings.stream().mapToLong(LedgerPosting::getAmount).sum();
            assertThat(sum)
                    .as("Capture %d: postings should sum exactly to capture amount", captureAmount)
                    .isEqualTo(captureAmount);
        }
    }

    @Test
    void WHK012_cancelledLinesExcludedFromAllocation() {
        // Cancelled lines are not ACTIVE and must not receive a posting.
        Booking booking = twoLineBooking();
        // Cancel the spa line
        BookingLine spaLine = booking.getLines().stream()
                .filter(l -> l.getVertical() == Vertical.SPA)
                .findFirst().orElseThrow();
        spaLine.setStatus(BookingLineStatus.CANCELLED);

        Payment payment = paymentWith(booking, 30000L);
        List<LedgerPosting> postings = ledgerService.postCapture(payment.getId());

        assertThat(postings).allMatch(p -> p.getVertical() != Vertical.SPA);
    }
}
