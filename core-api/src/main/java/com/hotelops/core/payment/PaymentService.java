package com.hotelops.core.payment;

import com.hotelops.core.booking.Booking;
import com.hotelops.core.booking.BookingRepository;
import com.hotelops.core.booking.BookingService;
import com.hotelops.core.common.enums.CaptureMode;
import com.hotelops.core.common.enums.PaymentStatus;
import com.hotelops.core.common.enums.RefundStatus;
import com.hotelops.core.common.error.StateChangedException;
import com.hotelops.core.ledger.LedgerService;
import com.hotelops.core.ledger.OutboxEvent;
import com.hotelops.core.ledger.OutboxEventRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * INV-005 — single-capture per auth: at most ONE capture per payment (full or partial).
 * Multi-capture and incremental-auth are OUT of scope; such requests are rejected.
 *
 * INV-006 — ledger posts on CAPTURE, not auth.
 * - AUTHORISATION → no posting.
 * - CANCELLATION of uncaptured auth → no posting (nothing was posted).
 * - CAPTURE → enqueue PAYMENT_CAPTURED outbox event → LedgerService posts REVENUE.
 * - REFUND  → enqueue REFUND_SETTLED outbox event  → LedgerService posts REFUND_REVERSAL.
 */
@Service
@Transactional
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final OutboxEventRepository outboxRepository;
    private final LedgerService ledgerService;

    public PaymentService(PaymentRepository paymentRepository,
                          RefundRepository refundRepository,
                          BookingRepository bookingRepository,
                          BookingService bookingService,
                          OutboxEventRepository outboxRepository,
                          LedgerService ledgerService) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.bookingRepository = bookingRepository;
        this.bookingService = bookingService;
        this.outboxRepository = outboxRepository;
        this.ledgerService = ledgerService;
    }

    /**
     * Create a new payment record (link/intent stage).
     * No posting at this stage — only a DB record is created.
     */
    public Payment createPayment(UUID bookingId, String shopperReference,
                                 String merchantReference, CaptureMode captureMode,
                                 long amountRequested, String currency) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new EntityNotFoundException("Booking not found: " + bookingId));
        Payment p = new Payment();
        p.setBooking(booking);
        p.setShopperReference(shopperReference);
        p.setMerchantReference(merchantReference);
        p.setCaptureMode(captureMode);
        p.setAmountRequested(amountRequested);
        p.setCurrency(currency != null ? currency : "GBP");
        return paymentRepository.save(p);
    }

    /**
     * Record an AUTHORISATION webhook result.
     * INV-006: no ledger posting on authorisation.
     */
    public Payment recordAuthorisation(String merchantReference, String pspReference,
                                       long amountAuthorised,
                                       OffsetDateTime authExpiresAt) {
        Payment p = paymentRepository.findByMerchantReference(merchantReference)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + merchantReference));
        p.setPspReference(pspReference);
        p.setAmountAuthorised(amountAuthorised);
        p.setAuthExpiresAt(authExpiresAt);
        p.setStatus(PaymentStatus.AUTHORISED);
        // INV-006: no outbox event, no ledger posting
        return paymentRepository.save(p);
    }

    /**
     * Capture a payment (full or partial).
     *
     * INV-005: rejects capture if payment is already in CAPTURED/PARTIALLY_REFUNDED/REFUNDED.
     * INV-003: re-validates that amount <= amount_authorised (DB constraint backs this up).
     * INV-006: enqueues PAYMENT_CAPTURED outbox event → LedgerService posts REVENUE.
     * INV-004: updates booking.amountPaid via BookingService.recalculateTotals.
     */
    public Payment capture(UUID paymentId, long captureAmount) {
        Payment p = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + paymentId));

        // INV-005: single-capture rule
        if (p.getStatus() == PaymentStatus.CAPTURED
                || p.getStatus() == PaymentStatus.PARTIALLY_REFUNDED
                || p.getStatus() == PaymentStatus.REFUNDED) {
            throw new StateChangedException(
                    "Payment " + paymentId + " has already been captured (INV-005); multi-capture rejected.",
                    p.getStatus());
        }
        if (p.getStatus() != PaymentStatus.AUTHORISED
                && !(p.getCaptureMode() == CaptureMode.IMMEDIATE
                     && p.getStatus() == PaymentStatus.PENDING)) {
            throw new StateChangedException(
                    "Payment " + paymentId + " is not in a capturable state: " + p.getStatus(),
                    p.getStatus());
        }
        if (captureAmount > p.getAmountAuthorised() && p.getStatus() == PaymentStatus.AUTHORISED) {
            throw new StateChangedException(
                    "Capture amount " + captureAmount + " exceeds authorised amount " + p.getAmountAuthorised(),
                    p.getAmountAuthorised());
        }

        p.setAmountCaptured(captureAmount);
        p.setStatus(PaymentStatus.CAPTURED);
        paymentRepository.save(p);

        // INV-006: enqueue outbox event for the ledger processor
        enqueueOutboxEvent("PAYMENT_CAPTURED", "PAYMENT", p.getId(), Map.of(
                "paymentId",         p.getId().toString(),
                "bookingId",         p.getBooking().getId().toString(),
                "amountCaptured",    captureAmount,
                "currency",          p.getCurrency(),
                "pspReference",      p.getPspReference() != null ? p.getPspReference() : "",
                "merchantReference", p.getMerchantReference()
        ));

        // INV-004: refresh booking roll-ups
        bookingService.recalculateTotals(p.getBooking());
        bookingRepository.save(p.getBooking());

        return p;
    }

    /**
     * Cancel an uncaptured authorisation.
     * INV-006: no ledger posting — nothing was captured, nothing to reverse.
     */
    public Payment cancelAuthorisation(UUID paymentId) {
        Payment p = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + paymentId));
        if (p.getAmountCaptured() > 0) {
            throw new StateChangedException(
                    "Cannot cancel a payment that has already been captured: " + paymentId,
                    p.getStatus());
        }
        p.setStatus(PaymentStatus.CANCELLED);
        // INV-006: no outbox event, no ledger posting
        return paymentRepository.save(p);
    }

    /**
     * Initiate a refund.
     * INV-006: enqueues REFUND_SETTLED outbox event → LedgerService posts REFUND_REVERSAL.
     * INV-004: updates booking.amountRefunded when the refund settles (via webhook).
     */
    public Refund createRefund(UUID paymentId, long refundAmount,
                               String merchantReference, String reason) {
        Payment p = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + paymentId));
        if (p.getAmountCaptured() - p.getAmountRefunded() < refundAmount) {
            throw new StateChangedException(
                    "Refund amount " + refundAmount + " exceeds remaining capturable amount",
                    p.getAmountCaptured() - p.getAmountRefunded());
        }
        Refund r = new Refund();
        r.setPayment(p);
        r.setAmount(refundAmount);
        r.setCurrency(p.getCurrency());
        r.setMerchantReference(merchantReference);
        r.setOriginalReference(p.getPspReference() != null ? p.getPspReference() : "");
        r.setReason(reason);
        return refundRepository.save(r);
    }

    /**
     * Settle a refund (called when REFUND webhook arrives).
     * INV-006: enqueues REFUND_SETTLED outbox event → LedgerService posts REFUND_REVERSAL.
     * INV-004: updates booking roll-ups.
     */
    public Refund settleRefund(String refundMerchantReference, String pspReference) {
        Refund r = refundRepository.findByMerchantReference(refundMerchantReference)
                .orElseThrow(() -> new EntityNotFoundException("Refund not found: " + refundMerchantReference));
        r.setPspReference(pspReference);
        r.setStatus(RefundStatus.REFUNDED);

        Payment p = r.getPayment();
        p.setAmountRefunded(p.getAmountRefunded() + r.getAmount());
        p.setStatus(p.getAmountRefunded() >= p.getAmountCaptured()
                ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED);
        paymentRepository.save(p);
        refundRepository.save(r);

        // INV-006: enqueue outbox event for the ledger processor
        enqueueOutboxEvent("REFUND_SETTLED", "REFUND", r.getId(), Map.of(
                "refundId",          r.getId().toString(),
                "paymentId",         p.getId().toString(),
                "bookingId",         p.getBooking().getId().toString(),
                "amount",            r.getAmount(),
                "currency",          r.getCurrency(),
                "pspReference",      pspReference,
                "originalReference", r.getOriginalReference(),
                "merchantReference", r.getMerchantReference()
        ));

        // INV-004: refresh booking roll-ups
        bookingService.recalculateTotals(p.getBooking());
        bookingRepository.save(p.getBooking());

        return r;
    }

    // -------------------------------------------------------------------------

    private void enqueueOutboxEvent(String eventType, String aggregateType,
                                    UUID aggregateId, Map<String, Object> payload) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType(eventType);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setPayload(payload);
        outboxRepository.save(event);
    }
}
