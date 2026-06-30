package com.hotelops.core.booking;

import com.hotelops.core.common.enums.BookingLineStatus;
import com.hotelops.core.common.enums.BookingStatus;
import com.hotelops.core.common.error.FolioNotCompletableException;
import com.hotelops.core.common.error.StateChangedException;
import com.hotelops.core.customer.Customer;
import com.hotelops.core.customer.CustomerRepository;
import com.hotelops.core.payment.PaymentRepository;
import com.hotelops.core.payment.RefundRepository;
import com.hotelops.core.product.Product;
import com.hotelops.core.product.ProductRepository;
import com.hotelops.core.product.vertical.VerticalStrategy;
import com.hotelops.core.product.vertical.VerticalStrategyRegistry;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * INV-003 — write-time revalidation: every createBookingLine / modifyBookingLine /
 * cancelBookingLine re-checks availability and price atomically.
 * If state moved since the caller's last read → throws {@link StateChangedException} (409).
 *
 * INV-004 — amount roll-ups: after any line change, this service recomputes
 * booking.totalAmount, booking.amountPaid, booking.amountRefunded and persists them.
 * Clients NEVER write these fields.
 */
@Service
@Transactional
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BookingLineRepository lineRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final VerticalStrategyRegistry strategyRegistry;

    public BookingService(BookingRepository bookingRepository,
                          BookingLineRepository lineRepository,
                          ProductRepository productRepository,
                          CustomerRepository customerRepository,
                          PaymentRepository paymentRepository,
                          RefundRepository refundRepository,
                          VerticalStrategyRegistry strategyRegistry) {
        this.bookingRepository = bookingRepository;
        this.lineRepository = lineRepository;
        this.productRepository = productRepository;
        this.customerRepository = customerRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.strategyRegistry = strategyRegistry;
    }

    /** Create a new booking (folio) for a customer. */
    public Booking createBooking(UUID customerId, String currency) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new EntityNotFoundException("Customer not found: " + customerId));
        Booking booking = new Booking();
        booking.setCustomer(customer);
        booking.setCurrency(currency != null ? currency : "GBP");
        return bookingRepository.save(booking);
    }

    /**
     * INV-003 + INV-004: add a line to the booking.
     *
     * 1. Lock-read the committed quantity for this product/window.
     * 2. Ask the vertical strategy for available capacity.
     * 3. If insufficient → 409 StateChangedException with current availability.
     * 4. Re-check the price via the strategy; if it changed → 409.
     * 5. Create the line with snapshot price.
     * 6. Recalculate amount roll-ups on the booking (INV-004).
     */
    public BookingLine addLine(UUID bookingId, UUID productId,
                               OffsetDateTime startsAt, OffsetDateTime endsAt,
                               int quantity) {
        Booking booking = lockedBooking(bookingId);
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + productId));

        VerticalStrategy strategy = strategyRegistry.forVertical(product.getVertical());

        // INV-003: re-check availability atomically
        int available = strategy.availableCapacity(productId, startsAt, endsAt);
        if (available < quantity) {
            throw new StateChangedException(
                    "Insufficient availability for product " + productId
                            + ": requested=" + quantity + " available=" + available,
                    available);
        }

        // INV-003: re-check current price snapshot
        long currentUnitPrice = strategy.calculateUnitPrice(productId, quantity, startsAt, endsAt);

        BookingLine line = new BookingLine();
        line.setBooking(booking);
        line.setProduct(product);
        line.setVertical(product.getVertical());
        line.setStartsAt(startsAt);
        line.setEndsAt(endsAt);
        line.setQuantity(quantity);
        line.setUnitPrice(currentUnitPrice);
        // Line total is owned by the vertical strategy: rooms multiply by nights, other
        // verticals stay unitPrice × quantity. Do NOT reintroduce a hardcoded multiply here
        // (KNOWN_LIMITATION_ROOM_PRICING.md).
        line.setLineAmount(strategy.calculateLineAmount(productId, quantity, startsAt, endsAt));
        line.setCurrency(product.getCurrency());

        lineRepository.save(line);
        recalculateTotals(booking);

        if (booking.getStatus() == BookingStatus.PENDING) {
            booking.setStatus(BookingStatus.CONFIRMED);
        }
        bookingRepository.save(booking);
        return line;
    }

    /**
     * INV-003 + INV-004: cancel a single booking line.
     * The booking remains open if other active lines exist.
     */
    public void cancelLine(UUID lineId) {
        BookingLine line = lineRepository.findById(lineId)
                .orElseThrow(() -> new EntityNotFoundException("BookingLine not found: " + lineId));
        line.setStatus(BookingLineStatus.CANCELLED);
        lineRepository.save(line);

        Booking booking = line.getBooking();
        recalculateTotals(booking);

        boolean allCancelled = booking.getLines().stream()
                .allMatch(l -> l.getStatus() == BookingLineStatus.CANCELLED);
        if (allCancelled) {
            booking.setStatus(BookingStatus.CANCELLED);
        }
        bookingRepository.save(booking);
    }

    /**
     * API-014 (ENM-003) — mark a single line rendered/done: ACTIVE → COMPLETED.
     *
     * Ungated and with NO folio side effect: completing a line never flips the booking
     * (the deliberate asymmetry with {@link #cancelLine}'s rollup — DESIGN_FOLIO_COMPLETION
     * §2; do not "tidy" it into a symmetric rollup). A CANCELLED line is terminal and cannot
     * be completed; an already-COMPLETED line is idempotent. The caller re-reads the folio.
     */
    public void completeLine(UUID bookingId, UUID lineId) {
        BookingLine line = lineRepository.findById(lineId)
                .orElseThrow(() -> new EntityNotFoundException("BookingLine not found: " + lineId));
        if (!line.getBooking().getId().equals(bookingId)) {
            throw new EntityNotFoundException(
                    "BookingLine " + lineId + " does not belong to booking " + bookingId);
        }
        if (line.getStatus() == BookingLineStatus.CANCELLED) {
            throw new StateChangedException(
                    "Line " + lineId + " is CANCELLED (terminal) and cannot be completed", null);
        }
        line.setStatus(BookingLineStatus.COMPLETED);
        lineRepository.save(line);
        // No recalculateTotals, no booking status change (T-A): completion has no folio effect.
    }

    /**
     * API-015 (ENM-002, INV-007) — close out a folio: CONFIRMED → COMPLETED.
     *
     * Write-time revalidation (INV-003 / charter §4): fails loudly WITHOUT writing unless both
     *   C1 — every non-CANCELLED line is COMPLETED, and
     *   C2 — {@code customerOwes == 0} (RX-003 D2; refund-driven {@code netRevenue} is
     *        irrelevant and correctly does not block)
     * hold. Idempotent success on an already-COMPLETED booking; CANCELLED (terminal) and
     * PENDING (empty, nothing rendered) are not completable.
     *
     * Deliberately does NOT call {@link #recalculateTotals}: that sum is ACTIVE-only
     * ({@code sumActiveLineAmounts}), so recomputing after lines are COMPLETED would drop the
     * completed lines' debt and zero the total. The freshly-loaded booking already carries the
     * server-maintained totals (line mutations and capture/refund webhooks keep them current),
     * read here inside the completion transaction — fresh, and correct for COMPLETED lines.
     */
    public void completeFolio(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new EntityNotFoundException("Booking not found: " + bookingId));

        if (booking.getStatus() == BookingStatus.COMPLETED) {
            return;   // idempotent success (Q2)
        }
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            // CANCELLED (terminal) or PENDING (empty folio, nothing to complete).
            throw new FolioNotCompletableException(
                    "Folio " + bookingId + " is " + booking.getStatus() + " and cannot be completed",
                    booking.getStatus(), booking.getCustomerOwes(), List.of());
        }

        // C1 — every non-CANCELLED line must be COMPLETED; collect the ACTIVE stragglers.
        List<UUID> incompleteLineIds = booking.getLines().stream()
                .filter(l -> l.getStatus() != BookingLineStatus.CANCELLED
                          && l.getStatus() != BookingLineStatus.COMPLETED)
                .map(BookingLine::getId)
                .toList();

        long customerOwes = booking.getCustomerOwes();   // C2 — RX-003 (read fresh, no recalc)
        boolean linesDone = incompleteLineIds.isEmpty();
        boolean settled = customerOwes == 0L;

        if (!linesDone || !settled) {
            throw new FolioNotCompletableException(
                    completionFailureMessage(incompleteLineIds, customerOwes),
                    booking.getStatus(), customerOwes, incompleteLineIds);
        }

        booking.setStatus(BookingStatus.COMPLETED);
        bookingRepository.save(booking);
    }

    private static String completionFailureMessage(List<UUID> incompleteLineIds, long customerOwes) {
        StringBuilder sb = new StringBuilder("Folio not completable:");
        if (!incompleteLineIds.isEmpty()) {
            sb.append(' ').append(incompleteLineIds.size()).append(" line(s) not COMPLETED (C1)");
        }
        if (customerOwes != 0L) {
            sb.append(incompleteLineIds.isEmpty() ? ' ' : ';').append(" customer owes ")
              .append(customerOwes).append(" (C2)");
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public Booking getById(UUID id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Booking not found: " + id));
    }

    // -------------------------------------------------------------------------
    // INV-004: amount roll-up (called after every line mutation and every capture/refund)
    // -------------------------------------------------------------------------

    /**
     * INV-004 — recompute the three amount columns on the booking.
     * Must be called inside the same write transaction as the triggering event.
     *
     * - totalAmount      = sum of ACTIVE line_amounts
     * - amountPaid       = sum of amount_captured across all payments for this booking
     * - amountRefunded   = sum of settled refund amounts for this booking's payments
     *
     * The folio "secured" figure (live authorised hold) is NO LONGER stored or refreshed here
     * (RX-004): it is derived on read in DtoMapper folio assembly as the sum of payment
     * amount_authorised over AUTHORISED-status payments only. Do not reintroduce a stored
     * roll-up — a stored derivation drifts (it inflated by counting spent IMMEDIATE auths).
     */
    public void recalculateTotals(Booking booking) {
        long total      = bookingRepository.sumActiveLineAmounts(booking.getId());
        long paid       = paymentRepository.sumCapturedForBooking(booking.getId());
        long refunded   = refundRepository.sumSettledRefundsForBooking(booking.getId());

        booking.setTotalAmount(total);
        booking.setAmountPaid(paid);
        booking.setAmountRefunded(refunded);
    }

    // -------------------------------------------------------------------------

    private Booking lockedBooking(UUID bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new EntityNotFoundException("Booking not found: " + bookingId));
    }
}
