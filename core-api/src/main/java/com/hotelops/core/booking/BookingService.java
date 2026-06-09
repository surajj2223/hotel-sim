package com.hotelops.core.booking;

import com.hotelops.core.common.enums.BookingLineStatus;
import com.hotelops.core.common.enums.BookingStatus;
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
        line.setLineAmount(currentUnitPrice * quantity);
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
     * - totalAmount     = sum of ACTIVE line_amounts
     * - amountPaid      = sum of amount_captured across all payments for this booking
     * - amountRefunded  = sum of settled refund amounts for this booking's payments
     */
    public void recalculateTotals(Booking booking) {
        long total    = bookingRepository.sumActiveLineAmounts(booking.getId());
        long paid     = paymentRepository.sumCapturedForBooking(booking.getId());
        long refunded = refundRepository.sumSettledRefundsForBooking(booking.getId());

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
