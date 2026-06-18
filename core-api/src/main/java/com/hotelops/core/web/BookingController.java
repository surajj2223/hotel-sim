package com.hotelops.core.web;

import com.hotelops.core.booking.Booking;
import com.hotelops.core.booking.BookingLineRepository;
import com.hotelops.core.booking.BookingService;
import com.hotelops.core.common.auth.HumanAuthorizationGate;
import com.hotelops.core.ledger.LedgerPostingRepository;
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
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final LedgerPostingRepository postingRepository;
    private final DtoMapper mapper;
    private final HumanAuthorizationGate humanAuth;

    public BookingController(BookingService bookingService,
                             BookingLineRepository lineRepository,
                             LedgerPostingRepository postingRepository,
                             DtoMapper mapper,
                             HumanAuthorizationGate humanAuth) {
        this.bookingService = bookingService;
        this.lineRepository = lineRepository;
        this.postingRepository = postingRepository;
        this.mapper = mapper;
        this.humanAuth = humanAuth;
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

    /**
     * API-014: mark a line rendered/done (ACTIVE → COMPLETED). Ungated — posts nothing,
     * moves no money, no folio side effect (mirrors the ungated {@code cancelLine}).
     * Completing a CANCELLED (terminal) line → 409.
     */
    @PostMapping("/{bookingId}/lines/{lineId}/complete")
    public FolioResponse completeLine(@PathVariable UUID bookingId, @PathVariable UUID lineId) {
        bookingService.completeLine(bookingId, lineId);
        return toFolio(bookingService.getById(bookingId));
    }

    /**
     * API-015: close out a folio (CONFIRMED → COMPLETED). Repercussive, INV-007 gated:
     * the human-auth signal is asserted BEFORE the service call, exactly like
     * {@code capturePayment} / {@code cancelAuthorisation}. Write-time revalidation C1+C2
     * fails loudly with current state (409); idempotent 200 on already-COMPLETED.
     */
    @PostMapping("/{bookingId}/complete")
    public FolioResponse completeFolio(
            @PathVariable UUID bookingId,
            @RequestHeader(value = HumanAuthorizationGate.HEADER_NAME, required = false) String humanAuthToken) {
        humanAuth.assertAuthorised(humanAuthToken, "completeFolio");
        bookingService.completeFolio(bookingId);
        return toFolio(bookingService.getById(bookingId));
    }

    private FolioResponse toFolio(Booking booking) {
        // WHK-016: per-line revenuePosted is derived from the booking's ledger postings.
        return mapper.toFolio(booking,
                lineRepository.findByBookingId(booking.getId()),
                postingRepository.findByBookingId(booking.getId()));
    }
}
