package com.hotelops.core.ledger;

import com.hotelops.core.booking.Booking;
import com.hotelops.core.booking.BookingRepository;
import com.hotelops.core.common.enums.PostingType;
import com.hotelops.core.payment.Payment;
import com.hotelops.core.payment.PaymentRepository;
import com.hotelops.core.payment.Refund;
import com.hotelops.core.payment.RefundRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * INV-006 — ledger posts on CAPTURE, not auth.
 *
 * This service is the ONLY place that creates {@link LedgerPosting} rows.
 * - AUTHORISATION  → no posting (no method here for it; that's intentional).
 * - CANCELLATION   → no posting.
 * - CAPTURE        → {@link #postCapture(UUID)} creates a REVENUE posting.
 * - REFUND settled → {@link #postRefund(UUID)} creates a REFUND_REVERSAL posting.
 *
 * Called by the {@link OutboxProcessor} which consumes outbox events asynchronously.
 */
@Service
@Transactional
public class LedgerService {

    private final LedgerPostingRepository postingRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final BookingRepository bookingRepository;

    public LedgerService(LedgerPostingRepository postingRepository,
                         PaymentRepository paymentRepository,
                         RefundRepository refundRepository,
                         BookingRepository bookingRepository) {
        this.postingRepository = postingRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.bookingRepository = bookingRepository;
    }

    /**
     * INV-006: CAPTURE → REVENUE posting.
     * Called by the outbox processor when it processes a PAYMENT_CAPTURED event.
     */
    public LedgerPosting postCapture(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + paymentId));
        Booking booking = payment.getBooking();

        LedgerPosting posting = new LedgerPosting();
        posting.setPostingType(PostingType.REVENUE);
        posting.setBooking(booking);
        posting.setPayment(payment);
        // Vertical from the first active booking line (folio-level fallback if lines load lazily)
        // For a robust implementation, the outbox payload carries the vertical.
        // Here we derive it from the booking's lines for correctness.
        posting.setVertical(booking.getCustomer() != null
                ? deriveVerticalFromBooking(booking) : null);
        posting.setAmount(payment.getAmountCaptured());   // positive — REVENUE
        posting.setCurrency(payment.getCurrency());
        posting.setPspReference(payment.getPspReference());
        posting.setMerchantReference(payment.getMerchantReference());
        posting.setNarration("Capture for payment " + payment.getMerchantReference());

        return postingRepository.save(posting);
    }

    /**
     * INV-006: REFUND settled → REFUND_REVERSAL posting (negative amount).
     * Called by the outbox processor when it processes a REFUND_SETTLED event.
     */
    public LedgerPosting postRefund(UUID refundId) {
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new EntityNotFoundException("Refund not found: " + refundId));
        Payment payment = refund.getPayment();
        Booking booking = payment.getBooking();

        LedgerPosting posting = new LedgerPosting();
        posting.setPostingType(PostingType.REFUND_REVERSAL);
        posting.setBooking(booking);
        posting.setRefund(refund);
        posting.setPayment(payment);
        posting.setVertical(deriveVerticalFromBooking(booking));
        posting.setAmount(-refund.getAmount());   // negative — REFUND_REVERSAL (SCH-051)
        posting.setCurrency(refund.getCurrency());
        posting.setPspReference(refund.getPspReference());
        posting.setMerchantReference(refund.getMerchantReference());
        posting.setNarration("Refund reversal for " + refund.getMerchantReference()
                + " (original: " + refund.getOriginalReference() + ")");

        return postingRepository.save(posting);
    }

    // -------------------------------------------------------------------------

    /**
     * Derive the vertical for a folio-level posting from the booking's first active line.
     * A proper implementation would carry the vertical in the outbox event payload.
     */
    private com.hotelops.core.common.enums.Vertical deriveVerticalFromBooking(Booking booking) {
        return booking.getLines().stream()
                .filter(l -> l.getStatus() == com.hotelops.core.common.enums.BookingLineStatus.ACTIVE
                          || l.getStatus() == com.hotelops.core.common.enums.BookingLineStatus.COMPLETED)
                .map(l -> l.getVertical())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot derive vertical from booking " + booking.getId() + ": no lines"));
    }
}
