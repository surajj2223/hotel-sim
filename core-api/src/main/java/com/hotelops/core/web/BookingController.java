package com.hotelops.core.web;

import com.hotelops.core.booking.Booking;
import com.hotelops.core.booking.BookingLineRepository;
import com.hotelops.core.booking.BookingService;
import com.hotelops.core.web.dto.BookingCreateRequest;
import com.hotelops.core.web.dto.BookingLineCreateRequest;
import com.hotelops.core.web.dto.FolioResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Booking (folio) endpoints — API-005 (open folio), API-006 (add line), API-007 (read).
 *
 * Wraps {@link BookingService}; the add-line write re-validates availability and price
 * atomically (INV-003) and recomputes folio totals (INV-004). A stale-state write throws
 * {@link com.hotelops.core.common.error.StateChangedException}, surfaced as 409 by the
 * advice. Lines are read explicitly for DTO assembly ({@code open-in-view: false}).
 */
@RestController
@RequestMapping("/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final BookingLineRepository lineRepository;
    private final DtoMapper mapper;

    public BookingController(BookingService bookingService,
                             BookingLineRepository lineRepository,
                             DtoMapper mapper) {
        this.bookingService = bookingService;
        this.lineRepository = lineRepository;
        this.mapper = mapper;
    }

    /** API-005: open an empty folio for a customer (PENDING, no lines, all amounts 0). */
    @PostMapping
    public ResponseEntity<FolioResponse> create(@Valid @RequestBody BookingCreateRequest request) {
        Booking booking = bookingService.createBooking(request.customerId(), request.currency());
        return ResponseEntity.status(HttpStatus.CREATED).body(toFolio(booking));
    }

    /** API-006: add a room line; revalidate availability + price at write time (INV-003/004). */
    @PostMapping("/{bookingId}/lines")
    public ResponseEntity<FolioResponse> addLine(@PathVariable UUID bookingId,
                                                 @Valid @RequestBody BookingLineCreateRequest request) {
        bookingService.addLine(bookingId, request.productId(),
                request.startsAt(), request.endsAt(), request.quantity());
        // Re-read the folio so totals/status reflect the write (INV-004).
        return ResponseEntity.status(HttpStatus.CREATED).body(toFolio(bookingService.getById(bookingId)));
    }

    /** API-007: read a folio with its lines and server-derived amounts. */
    @GetMapping("/{bookingId}")
    public FolioResponse get(@PathVariable UUID bookingId) {
        return toFolio(bookingService.getById(bookingId));
    }

    private FolioResponse toFolio(Booking booking) {
        return mapper.toFolio(booking, lineRepository.findByBookingId(booking.getId()));
    }
}
