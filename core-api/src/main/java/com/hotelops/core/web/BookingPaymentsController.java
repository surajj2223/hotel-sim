package com.hotelops.core.web;

import com.hotelops.core.common.auth.HumanAuthorizationGate;
import com.hotelops.core.payment.Payment;
import com.hotelops.core.payment.PaymentOrchestrator;
import com.hotelops.core.payment.PaymentRepository;
import com.hotelops.core.payment.RefundRepository;
import com.hotelops.core.web.dto.PaymentLinkCreateRequest;
import com.hotelops.core.web.dto.PaymentResponse;
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

import java.util.List;
import java.util.UUID;

/**
 * Payment endpoints rooted at a booking — API-008 (createPaymentLink) and
 * API-009 (listBookingPayments, ungated).
 *
 * The repercussive write here ({@code createPaymentLink}) is human-gated per INV-007 via
 * {@code X-Human-Auth}. {@code merchantReference} is server-minted (decision 2); the
 * caller never supplies it. {@code paymentLinkId} is minted by {@code payments-sim} on the
 * outbound PSP-001 call (Feature 2) — {@link PaymentOrchestrator} persists the PENDING
 * payment, calls the PSP outside any transaction (PSP-006), then stamps the link.
 */
@RestController
@RequestMapping("/bookings/{bookingId}/payments")
public class BookingPaymentsController {

    private final PaymentOrchestrator paymentOrchestrator;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final HumanAuthorizationGate humanAuth;
    private final DtoMapper mapper;

    public BookingPaymentsController(PaymentOrchestrator paymentOrchestrator,
                                     PaymentRepository paymentRepository,
                                     RefundRepository refundRepository,
                                     HumanAuthorizationGate humanAuth,
                                     DtoMapper mapper) {
        this.paymentOrchestrator = paymentOrchestrator;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.humanAuth = humanAuth;
        this.mapper = mapper;
    }

    /** API-008: create a payment link against a folio; PSP mints paymentLinkId (Feature 2). */
    @PostMapping
    public ResponseEntity<PaymentResponse> createPaymentLink(
            @PathVariable UUID bookingId,
            @RequestHeader(value = HumanAuthorizationGate.HEADER_NAME, required = false) String humanAuthToken,
            @Valid @RequestBody PaymentLinkCreateRequest request) {
        humanAuth.assertAuthorised(humanAuthToken, "createPaymentLink");
        Payment payment = paymentOrchestrator.createPaymentLink(
                bookingId, request.amount(), request.currency(), request.captureMode());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(payment));
    }

    /** API-009: list all payments (and their refunds) on a folio. Read; not gated. */
    @GetMapping
    public List<PaymentResponse> listBookingPayments(@PathVariable UUID bookingId) {
        return paymentRepository.findByBookingId(bookingId).stream()
                .map(this::toResponse)
                .toList();
    }

    private PaymentResponse toResponse(Payment payment) {
        return mapper.toPaymentResponse(
                payment,
                refundRepository.findByPaymentId(payment.getId()));
    }
}
