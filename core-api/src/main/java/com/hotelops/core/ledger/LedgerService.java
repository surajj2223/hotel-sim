package com.hotelops.core.ledger;

import com.hotelops.core.booking.Booking;
import com.hotelops.core.booking.BookingLine;
import com.hotelops.core.common.enums.BookingLineStatus;
import com.hotelops.core.common.enums.PostingType;
import com.hotelops.core.common.enums.Vertical;
import com.hotelops.core.payment.Payment;
import com.hotelops.core.payment.PaymentLine;
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

        // WHK-016: when the payment carries scoped coverage, allocate the captured amount
        // across exactly those lines; otherwise WHK-012 fill-by-line-order (unchanged).
        List<LedgerPosting> postings = payment.getCoverageLines().isEmpty()
                ? allocate(
                        activeLinesSorted(booking),
                        payment.getAmountCaptured(),
                        PostingType.REVENUE,
                        booking, payment, null,
                        payment.getCurrency(),
                        payment.getPspReference(),
                        payment.getMerchantReference())
                : allocateScoped(
                        payment.getCoverageLines(),
                        payment.getAmountCaptured(),
                        PostingType.REVENUE,
                        booking, payment, null,
                        payment.getCurrency(),
                        payment.getPspReference(),
                        payment.getMerchantReference());

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

        // WHK-016 (D2): a refund reverses revenue against the lines the PARENT PAYMENT posted
        // to. When the parent has scoped coverage, reverse against exactly those lines (scaled
        // to the refund amount); otherwise WHK-012 fill-by-line-order (unchanged). Trap E: the
        // scope comes from the parent payment, NEVER re-derived from the booking's active lines.
        List<LedgerPosting> postings = payment.getCoverageLines().isEmpty()
                ? allocate(
                        activeLinesSorted(booking),
                        refund.getAmount(),
                        PostingType.REFUND_REVERSAL,
                        booking, payment, refund,
                        refund.getCurrency(),
                        refund.getPspReference(),
                        refund.getMerchantReference())
                : allocateScoped(
                        payment.getCoverageLines(),
                        refund.getAmount(),
                        PostingType.REFUND_REVERSAL,
                        booking, payment, refund,
                        refund.getCurrency(),
                        refund.getPspReference(),
                        refund.getMerchantReference());

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

            result.add(newPosting(type, booking, line, payment, refund, assigned,
                    currency, pspReference, merchantReference));
            remaining -= assigned;
        }
        return result;
    }

    /**
     * WHK-016 scoped allocation. Distributes {@code eventAmount} across exactly the covered
     * lines, proportionally to each line's recorded coverage amount. All arithmetic is in
     * minor units; each line's share is floored and the single rounding remainder is assigned
     * deterministically to the first covered line (ordered by line {@code created_at}) so the
     * Σ-postings == event-amount guard always holds and results are reproducible (Trap C).
     *
     * <p>For a full capture ({@code eventAmount == Σ coverage}), each line receives exactly its
     * coverage amount and there is no remainder. For a partial capture, shares scale down.
     */
    private List<LedgerPosting> allocateScoped(
            List<PaymentLine> coverage,
            long eventAmount,
            PostingType type,
            Booking booking,
            Payment payment,
            Refund refund,
            String currency,
            String pspReference,
            String merchantReference) {

        // Deterministic order: by booking line created_at ascending (remainder → first).
        List<PaymentLine> ordered = coverage.stream()
                .sorted(Comparator.comparing(pl -> pl.getBookingLine().getCreatedAt()))
                .toList();

        long coverageSum = ordered.stream().mapToLong(PaymentLine::getAmount).sum();
        if (coverageSum <= 0) {
            throw new IllegalStateException("Scoped coverage sums to " + coverageSum
                    + " for payment " + payment.getId() + "; cannot allocate.");
        }

        long[] shares = new long[ordered.size()];
        long allocated = 0;
        for (int i = 0; i < ordered.size(); i++) {
            shares[i] = Math.floorDiv(eventAmount * ordered.get(i).getAmount(), coverageSum);
            allocated += shares[i];
        }
        // Deterministic remainder placement (Trap C): the whole, single remainder to line 0.
        shares[0] += (eventAmount - allocated);

        List<LedgerPosting> result = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            long assigned = shares[i];
            if (assigned <= 0) continue;
            BookingLine line = ordered.get(i).getBookingLine();
            result.add(newPosting(type, booking, line, payment, refund, assigned,
                    currency, pspReference, merchantReference));
        }
        return result;
    }

    /** Build one per-line posting. REVENUE amounts are positive; REFUND_REVERSAL negated. */
    private LedgerPosting newPosting(PostingType type, Booking booking, BookingLine line,
                                     Payment payment, Refund refund, long assigned,
                                     String currency, String pspReference, String merchantReference) {
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
        return p;
    }

    private String buildNarration(PostingType type, Vertical vertical, String merchantReference) {
        return type == PostingType.REVENUE
                ? "Capture revenue (" + vertical + ") for " + merchantReference
                : "Refund reversal (" + vertical + ") for " + merchantReference;
    }
}
