package com.hotelops.core.web;

import com.hotelops.core.booking.Booking;
import com.hotelops.core.booking.BookingLine;
import com.hotelops.core.customer.Customer;
import com.hotelops.core.customer.CustomerPreference;
import com.hotelops.core.ledger.LedgerPosting;
import com.hotelops.core.payment.Payment;
import com.hotelops.core.payment.Refund;
import com.hotelops.core.product.Product;
import com.hotelops.core.product.ProductRoom;
import com.hotelops.core.product.ProductSpa;
import com.hotelops.core.web.dto.AvailabilityResult;
import com.hotelops.core.web.dto.BookingLineResponse;
import com.hotelops.core.web.dto.CustomerResponse;
import com.hotelops.core.web.dto.FolioResponse;
import com.hotelops.core.web.dto.PaymentResponse;
import com.hotelops.core.web.dto.PreferenceResponse;
import com.hotelops.core.web.dto.RefundResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Entity -> DTO mapping for the HTTP boundary. Entities are NEVER serialised directly
 * (WAVE0_02_OPENAPI.yaml conventions). This mapper holds no business logic; callers
 * supply any server-derived values (e.g. availability/price) computed by the service layer.
 *
 * Lazy associations are read by id only ({@code getCustomer().getId()},
 * {@code getProduct().getId()}) — that does not initialise the proxy, so it is safe with
 * {@code open-in-view: false}. Collections are passed in explicitly, never walked lazily.
 */
@Component
public class DtoMapper {

    public CustomerResponse toCustomerResponse(Customer c) {
        return new CustomerResponse(
                c.getId(),
                c.getShopperReference(),
                c.getFullName(),
                c.getEmail(),
                c.getPhone(),
                c.getCreatedAt());
    }

    public PreferenceResponse toPreferenceResponse(CustomerPreference p) {
        return new PreferenceResponse(p.getPrefKey(), p.getPrefValue());
    }

    /**
     * Build an availability row. {@code unitPrice} and {@code availableUnits} are computed
     * by the caller via the vertical strategy (INV-003 read side), not derived here.
     */
    public AvailabilityResult toAvailabilityResult(Product product, long unitPrice, int availableUnits) {
        AvailabilityResult.RoomAttributes roomAttrs = null;
        AvailabilityResult.SpaAttributes spaAttrs = null;
        if (product instanceof ProductRoom room) {
            roomAttrs = new AvailabilityResult.RoomAttributes(
                    room.getFloorBand(),
                    room.getBedType(),
                    room.isQuiet(),
                    room.getMaxOccupancy());
        } else if (product instanceof ProductSpa spa) {
            spaAttrs = new AvailabilityResult.SpaAttributes(
                    spa.getTreatmentKind(),
                    spa.getDurationMinutes(),
                    spa.getTherapistGender(),
                    spa.getConcurrentSlots());
        }
        return new AvailabilityResult(
                product.getId(),
                product.getVertical(),
                product.getName(),
                unitPrice,
                product.getCurrency(),
                availableUnits,
                roomAttrs,
                spaAttrs);
    }

    /** Line with no ledger context — {@code revenuePosted} defaults to 0 (no postings supplied). */
    public BookingLineResponse toBookingLineResponse(BookingLine line) {
        return toBookingLineResponse(line, 0L);
    }

    /**
     * WHK-016 — line with its DERIVED {@code revenuePosted} supplied by the caller (the net
     * ledger revenue summed for this line). Held outside the entity; computed at assembly.
     */
    public BookingLineResponse toBookingLineResponse(BookingLine line, long revenuePosted) {
        return new BookingLineResponse(
                line.getId(),
                line.getProduct().getId(),   // lazy-proxy id: no initialisation
                line.getVertical(),
                line.getStatus(),
                line.getStartsAt(),
                line.getEndsAt(),
                line.getQuantity(),
                line.getUnitPrice(),
                line.getLineAmount(),
                line.getCurrency(),
                revenuePosted);
    }

    /** Assemble a folio from the booking plus its lines (fetched explicitly by the caller). */
    public FolioResponse toFolio(Booking booking, List<BookingLine> lines) {
        return toFolio(booking, lines, List.of());
    }

    /**
     * WHK-016 — assemble a folio, deriving each line's {@code revenuePosted} from the booking's
     * ledger postings (fetched explicitly by the caller via
     * {@code LedgerPostingRepository.findByBookingId}). Postings are summed per booking line —
     * REVENUE positive, REFUND_REVERSAL already stored negated (Trap D: one aggregate per line,
     * a refunded line nets down rather than adding). Folio-level postings (null line) are
     * ignored for the per-line figure.
     */
    public FolioResponse toFolio(Booking booking, List<BookingLine> lines, List<LedgerPosting> postings) {
        Map<UUID, Long> revenueByLine = postings.stream()
                .filter(p -> p.getBookingLine() != null)
                .collect(Collectors.groupingBy(
                        p -> p.getBookingLine().getId(),
                        Collectors.summingLong(LedgerPosting::getAmount)));
        List<BookingLineResponse> lineDtos = lines.stream()
                .map(line -> toBookingLineResponse(line, revenueByLine.getOrDefault(line.getId(), 0L)))
                .toList();
        return new FolioResponse(
                booking.getId(),
                booking.getCustomer().getId(),   // lazy-proxy id: no initialisation
                booking.getStatus(),
                booking.getCurrency(),
                booking.getTotalAmount(),
                booking.getAmountPaid(),
                booking.getAmountRefunded(),
                booking.getAmountAuthorised(),
                booking.getCustomerOwes(),
                booking.getNetRevenue(),
                lineDtos);
    }

    public RefundResponse toRefundResponse(Refund r) {
        return new RefundResponse(
                r.getId(),
                r.getPayment().getId(),    // lazy-proxy id: no initialisation
                r.getAmount(),
                r.getCurrency(),
                r.getStatus(),
                r.getMerchantReference(),
                r.getPspReference(),
                r.getOriginalReference(),
                r.getReason(),
                r.getCreatedAt());
    }

    /**
     * Assemble a payment with its refunds (fetched explicitly by the caller via
     * RefundRepository.findByPaymentId to avoid initialising the lazy collection).
     */
    public PaymentResponse toPaymentResponse(Payment p, List<Refund> refunds) {
        List<RefundResponse> refundDtos = refunds.stream()
                .map(this::toRefundResponse)
                .toList();
        return new PaymentResponse(
                p.getId(),
                p.getBooking().getId(),     // lazy-proxy id: no initialisation
                p.getStatus(),
                p.getCaptureMode(),
                p.getCurrency(),
                p.getAmountRequested(),
                p.getAmountAuthorised(),
                p.getAmountCaptured(),
                p.getAmountRefunded(),
                p.getShopperReference(),
                p.getMerchantReference(),
                p.getPspReference(),
                p.getPaymentLinkId(),
                p.getAuthExpiresAt(),
                p.getCreatedAt(),
                refundDtos);
    }
}
