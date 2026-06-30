package com.hotelops.core.reporting;

import com.hotelops.core.booking.Booking;
import com.hotelops.core.booking.BookingLine;
import com.hotelops.core.booking.BookingRepository;
import com.hotelops.core.common.enums.PostingType;
import com.hotelops.core.common.enums.Vertical;
import com.hotelops.core.ledger.LedgerPostingRepository;
import com.hotelops.core.payment.PaymentLineRepository;
import com.hotelops.core.web.dto.RevenueReport;
import com.hotelops.core.web.dto.RevenueReport.RevenueByVertical;
import com.hotelops.core.web.dto.RevenueReport.RevenueTotals;
import com.hotelops.core.web.dto.RevenueReport.Window;
import com.hotelops.core.web.dto.UnpaidBookings;
import com.hotelops.core.web.dto.UnpaidBookings.UnpaidBooking;
import com.hotelops.core.web.dto.UnpaidBookings.UnpaidBookingLine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Reporting reads — API-016 getRevenue + API-017 listUnpaidBookings (charter §9).
 *
 * Read-only assembly over existing data access; NO schema change. Reuses
 * {@link LedgerPostingRepository#sumByVertical} (the grouped half-open {@code [from, to)} window
 * query) and {@link BookingRepository#findUnpaid} (the frozen {@code total > paid} predicate)
 * verbatim, and two new batched per-line aggregates. All amounts are minor units. The derived
 * fields ({@code net}, {@code lineOwes}) are computed here and never persisted.
 */
@Service
public class ReportingService {

    /** POC is single-currency; the schema carries GBP everywhere. */
    private static final String CURRENCY = "GBP";

    private final LedgerPostingRepository ledgerPostingRepository;
    private final BookingRepository bookingRepository;
    private final PaymentLineRepository paymentLineRepository;

    public ReportingService(LedgerPostingRepository ledgerPostingRepository,
                            BookingRepository bookingRepository,
                            PaymentLineRepository paymentLineRepository) {
        this.ledgerPostingRepository = ledgerPostingRepository;
        this.bookingRepository = bookingRepository;
        this.paymentLineRepository = paymentLineRepository;
    }

    /**
     * API-016 — revenue split by vertical over the half-open {@code [from, to)} posting-time
     * window. {@code gross} = Σ REVENUE; {@code refundedTotal} = |Σ REFUND_REVERSAL| (sign-flip,
     * reversals stored negative); {@code net = gross − refundedTotal} (derived). Totals roll the
     * same identity across verticals. Zero-activity verticals are omitted.
     */
    @Transactional(readOnly = true)
    public RevenueReport getRevenue(OffsetDateTime from, OffsetDateTime to) {
        Map<Vertical, Long> gross = sumToMap(
                ledgerPostingRepository.sumByVertical(PostingType.REVENUE, from, to));
        Map<Vertical, Long> reversals = sumToMap(
                ledgerPostingRepository.sumByVertical(PostingType.REFUND_REVERSAL, from, to));

        // Union of verticals that saw any activity (deterministic order: enum declaration order).
        var verticals = new TreeSet<Vertical>();
        verticals.addAll(gross.keySet());
        verticals.addAll(reversals.keySet());

        List<RevenueByVertical> byVertical = new ArrayList<>();
        long totalGross = 0L;
        long totalRefunded = 0L;
        for (Vertical v : verticals) {
            long g = gross.getOrDefault(v, 0L);
            long refundedTotal = Math.abs(reversals.getOrDefault(v, 0L)); // reversals stored negative
            byVertical.add(new RevenueByVertical(v, g, refundedTotal, g - refundedTotal));
            totalGross += g;
            totalRefunded += refundedTotal;
        }

        RevenueTotals totals = new RevenueTotals(totalGross, totalRefunded, totalGross - totalRefunded);
        return new RevenueReport(new Window(from, to), CURRENCY, byVertical, totals);
    }

    /**
     * API-017 — every booking with {@code total_amount > amount_paid}, each with its per-line
     * owed/posted/held breakdown. The two per-line aggregates are batched once across all unpaid
     * lines (no N+1).
     */
    @Transactional(readOnly = true)
    public UnpaidBookings listUnpaid() {
        List<Booking> bookings = bookingRepository.findUnpaid();

        List<UUID> lineIds = bookings.stream()
                .flatMap(b -> b.getLines().stream())
                .map(BookingLine::getId)
                .toList();

        Map<UUID, Long> revenueByLine = lineIds.isEmpty()
                ? Map.of() : lineSumToMap(ledgerPostingRepository.sumRevenueByLine(lineIds));
        Map<UUID, Long> heldByLine = lineIds.isEmpty()
                ? Map.of() : lineSumToMap(paymentLineRepository.sumHeldAuthByLine(lineIds));

        List<UnpaidBooking> out = new ArrayList<>(bookings.size());
        for (Booking b : bookings) {
            List<UnpaidBookingLine> lines = new ArrayList<>();
            for (BookingLine bl : b.getLines()) {
                long posted = revenueByLine.getOrDefault(bl.getId(), 0L);
                long held = heldByLine.getOrDefault(bl.getId(), 0L);
                long owes = bl.getLineAmount() - posted; // CAPTURED ONLY — held auth does not reduce owes
                lines.add(new UnpaidBookingLine(
                        bl.getId(), bl.getVertical(), bl.getLineAmount(), posted, owes, held));
            }
            out.add(new UnpaidBooking(
                    b.getId(),
                    b.getCustomer().getShopperReference(),
                    b.getCustomer().getFullName(),
                    b.getCurrency(),
                    b.getTotalAmount(),
                    b.getAmountPaid(),
                    b.getCustomerOwes(),
                    lines));
        }
        return new UnpaidBookings(out);
    }

    /** {@code [Vertical, Long]} aggregate rows → map. */
    private static Map<Vertical, Long> sumToMap(List<Object[]> rows) {
        Map<Vertical, Long> map = new EnumMap<>(Vertical.class);
        for (Object[] row : rows) {
            map.put((Vertical) row[0], ((Number) row[1]).longValue());
        }
        return map;
    }

    /** {@code [UUID, Long]} aggregate rows → map. */
    private static Map<UUID, Long> lineSumToMap(List<Object[]> rows) {
        Map<UUID, Long> map = new java.util.HashMap<>();
        for (Object[] row : rows) {
            map.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return map;
    }
}
