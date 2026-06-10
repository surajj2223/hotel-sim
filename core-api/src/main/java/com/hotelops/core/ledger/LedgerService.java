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
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * INV-006 — ledger posts on CAPTURE, not auth.
 *
 * Per WHK-007/009/012: one posting per covered booking line, fill-by-line-order
 * (booking_line.created_at ascending). GAP-1 fix: folio-level single postings removed.
 *
 * Called by {@link OutboxEventHandler} inside a @Transactional boundary.
 */
@Service
@Transactional
public class LedgerService {

    private final LedgerPostingRepository postingRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

    public LedgerService(LedgerPostingRepository postingRepository,
                         PaymentRepository paymentRepository,
                         RefundRepository refundRepository) {
        this.postingRepository = postingRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
    }

    /**
     * INV-006 / WHK-007 / WHK-012: CAPTURE → per-line REVENUE postings.
     *
     * Walks active booking lines (created_at ascending), assigns
     * min(remaining, lineAmount) to each, producing one REVENUE posting per
     * non-zero assignment. Allocations sum exactly to amountCaptured.
     */
    public List<LedgerPosting> postCapture(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + paymentId));
        Booking booking = payment.getBooking();
        List<BookingLine> activeLines = activeLinesSorted(booking);

        List<LedgerPosting> postings = allocate(
                activeLines,
                payment.getAmountCaptured(),
                PostingType.REVENUE,
                booking, payment, null,
                payment.getCurrency(),
                payment.getPspReference(),
                payment.getMerchantReference()
        );

        long sum = postings.stream().mapToLong(LedgerPosting::getAmount).sum();
        if (sum != payment.getAmountCaptured()) {
            throw new IllegalStateException(
                    "Capture allocation mismatch for payment " + paymentId
                    + ": allocated " + sum + " but captured " + payment.getAmountCaptured());
        }
        return postingRepository.saveAll(postings);
    }

    /**
     * INV-006 / WHK-009 / WHK-012: REFUND settled → per-line REFUND_REVERSAL postings (negative).
     *
     * Walks the same line order; amounts are negative and sum to -refund.getAmount().
     */
    public List<LedgerPosting> postRefund(UUID refundId) {
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new EntityNotFoundException("Refund not found: " + refundId));
        Payment payment = refund.getPayment();
        Booking booking = payment.getBooking();
        List<BookingLine> activeLines = activeLinesSorted(booking);

        List<LedgerPosting> postings = allocate(
                activeLines,
                refund.getAmount(),
                PostingType.REFUND_REVERSAL,
                booking, payment, refund,
                refund.getCurrency(),
                refund.getPspReference(),
                refund.getMerchantReference()
        );

        long sumAbs = postings.stream().mapToLong(p -> -p.getAmount()).sum();
        if (sumAbs != refund.getAmount()) {
            throw new IllegalStateException(
                    "Refund allocation mismatch for refund " + refundId
                    + ": allocated " + sumAbs + " but refund amount " + refund.getAmount());
        }
        return postingRepository.saveAll(postings);
    }

    // -------------------------------------------------------------------------

    /** Returns ACTIVE lines sorted by created_at ascending (WHK-012). */
    private List<BookingLine> activeLinesSorted(Booking booking) {
        return booking.getLines().stream()
                .filter(l -> l.getStatus() == BookingLineStatus.ACTIVE)
                .sorted(Comparator.comparing(BookingLine::getCreatedAt))
                .toList();
    }

    /**
     * Fill-by-line-order allocation (WHK-012).
     *
     * For REVENUE: amounts are positive.
     * For REFUND_REVERSAL: amounts are negated before storage.
     */
    private List<LedgerPosting> allocate(
            List<BookingLine> lines,
            long totalAmount,
            PostingType type,
            Booking booking,
            Payment payment,
            Refund refund,
            String currency,
            String pspReference,
            String merchantReference) {

        List<LedgerPosting> result = new ArrayList<>();
        long remaining = totalAmount;

        for (BookingLine line : lines) {
            if (remaining <= 0) break;
            long assigned = Math.min(remaining, line.getLineAmount());
            if (assigned <= 0) continue;

            LedgerPosting p = new LedgerPosting();
            p.setPostingType(type);
            p.setBooking(booking);
            p.setBookingLine(line);
            p.setPayment(payment);
            p.setRefund(refund);
            p.setVertical(line.getVertical());
            p.setAmount(type == PostingType.REVENUE ? assigned : -assigned);
            p.setCurrency(currency);
            p.setPspReference(pspReference);
            p.setMerchantReference(merchantReference);
            p.setNarration(buildNarration(type, line.getVertical(), merchantReference));

            result.add(p);
            remaining -= assigned;
        }
        return result;
    }

    private String buildNarration(PostingType type, Vertical vertical, String merchantReference) {
        return type == PostingType.REVENUE
                ? "Capture revenue (" + vertical + ") for " + merchantReference
                : "Refund reversal (" + vertical + ") for " + merchantReference;
    }
}
