package com.hotelops.core.web;

import com.hotelops.core.booking.Booking;
import com.hotelops.core.booking.BookingLine;
import com.hotelops.core.customer.Customer;
import com.hotelops.core.customer.CustomerPreference;
import com.hotelops.core.product.Product;
import com.hotelops.core.product.ProductRoom;
import com.hotelops.core.web.dto.AvailabilityResult;
import com.hotelops.core.web.dto.BookingLineResponse;
import com.hotelops.core.web.dto.CustomerResponse;
import com.hotelops.core.web.dto.FolioResponse;
import com.hotelops.core.web.dto.PreferenceResponse;
import org.springframework.stereotype.Component;

import java.util.List;

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
        AvailabilityResult.RoomAttributes attrs = null;
        if (product instanceof ProductRoom room) {
            attrs = new AvailabilityResult.RoomAttributes(
                    room.getFloorBand(),
                    room.getBedType(),
                    room.isQuiet(),
                    room.getMaxOccupancy());
        }
        return new AvailabilityResult(
                product.getId(),
                product.getVertical(),
                product.getName(),
                unitPrice,
                product.getCurrency(),
                availableUnits,
                attrs);
    }

    public BookingLineResponse toBookingLineResponse(BookingLine line) {
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
                line.getCurrency());
    }

    /** Assemble a folio from the booking plus its lines (fetched explicitly by the caller). */
    public FolioResponse toFolio(Booking booking, List<BookingLine> lines) {
        List<BookingLineResponse> lineDtos = lines.stream()
                .map(this::toBookingLineResponse)
                .toList();
        return new FolioResponse(
                booking.getId(),
                booking.getCustomer().getId(),   // lazy-proxy id: no initialisation
                booking.getStatus(),
                booking.getCurrency(),
                booking.getTotalAmount(),
                booking.getAmountPaid(),
                booking.getAmountRefunded(),
                booking.getBalance(),
                lineDtos);
    }
}
