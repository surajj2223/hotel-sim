package com.hotelops.core.payment;

import com.hotelops.core.booking.Booking;
import com.hotelops.core.booking.BookingLine;
import com.hotelops.core.booking.BookingLineRepository;
import com.hotelops.core.booking.BookingRepository;
import com.hotelops.core.booking.BookingService;
import com.hotelops.core.common.enums.BookingLineStatus;
import com.hotelops.core.common.enums.CaptureMode;
import com.hotelops.core.common.enums.PaymentStatus;
import com.hotelops.core.common.enums.RefundStatus;
import com.hotelops.core.common.error.StateChangedException;
import com.hotelops.core.ledger.OutboxEvent;
import com.hotelops.core.ledger.OutboxEventRepository;
import com.hotelops.core.product.vertical.VerticalStrategyRegistry;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
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
 *
 * WHK-015 — async completion model: operator-facing methods are split into
 *   request* (called by the 202 controller — validates only) and
 *   settle*  (called by the inbound webhook — flips state + enqueues outbox).
 * Refund was already split (createRefund / settleRefund); capture and cancel now match.
 *
 * PSP-006 — transaction boundaries are <b>per-method</b>, never class-level. The outbound
 * PSP HTTP call (in {@link com.hotelops.core.payment.psp.PspGateway}) must sit OUTSIDE any
 * open transaction; {@link PaymentOrchestrator} sequences {@code persist (tx1) → commit →
 * HTTP → stamp (tx2)} by invoking the proxied public methods on this bean from a separate,
 * non-transactional bean. A class-level {@code @Transactional} would re-wrap the whole
 * orchestration in one transaction and pin a Hikari connection across the network call —
 * the GAP-2 trap in a new guise (WAVE0_05 §3.1). {@code REQUIRES_NEW} is explicitly not a
 * fix (§3.1.2): tx1 stays logically open on the thread.
 */
@Service
public class PaymentService {

    /** Prefix matching the PSP envelope examples in WAVE0_03 §3. */
    private static final String MERCHANT_REF_PREFIX = "MR-";

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final BookingRepository bookingRepository;
    private final BookingLineRepository bookingLineRepository;
    private final PaymentLineRepository paymentLineRepository;
    private final BookingService bookingService;
    private final OutboxEventRepository outboxRepository;
    private final VerticalStrategyRegistry strategyRegistry;

    public PaymentService(PaymentRepository paymentRepository,
                          RefundRepository refundRepository,
                          BookingRepository bookingRepository,
                          BookingLineRepository bookingLineRepository,
                          PaymentLineRepository paymentLineRepository,
                          BookingService bookingService,
                          OutboxEventRepository outboxRepository,
                          VerticalStrategyRegistry strategyRegistry) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.bookingRepository = bookingRepository;
        this.bookingLineRepository = bookingLineRepository;
        this.paymentLineRepository = paymentLineRepository;
        this.bookingService = bookingService;
        this.outboxRepository = outboxRepository;
        this.strategyRegistry = strategyRegistry;
    }

    // -------------------------------------------------------------------------
    // API-008: createPaymentLink — orchestrate booking lookup, shopperReference,
    // captureMode defaulting (per the booking's vertical strategy), and minting.
    // -------------------------------------------------------------------------

    /**
     * API-008 high-level entrypoint for the controller. Looks up the booking, copies
     * {@code shopperReference} from the customer, defaults {@code captureMode} from the
     * first active line's vertical strategy when omitted, mints {@code merchantReference},
     * and creates a {@code PENDING} payment. {@code paymentLinkId} stays {@code null}
     * until the PSP mints it (Feature 2).
     */
    @Transactional
    public Payment createPaymentLink(UUID bookingId, long amount, String currency,
                                     CaptureMode captureModeOverride) {
        return createPaymentLink(bookingId, amount, currency, captureModeOverride, null);
    }

    /**
     * WHK-016 overload — same as above, but the payment MAY carry scoped {@code coverage}
     * declaring exactly which booking lines it settles and for how much. When non-empty, the
     * coverage amounts must sum to {@code amount} (rejected 400 otherwise) and are persisted as
     * {@link PaymentLine} rows that drive scoped ledger allocation. When {@code null}/empty,
     * the payment is folio-wide and the WHK-012 fill-by-line-order fallback applies unchanged.
     */
    @Transactional
    public Payment createPaymentLink(UUID bookingId, long amount, String currency,
                                     CaptureMode captureModeOverride, List<LineCoverage> coverage) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new EntityNotFoundException("Booking not found: " + bookingId));
        String shopperReference = booking.getCustomer().getShopperReference();
        CaptureMode mode = (captureModeOverride != null)
                ? captureModeOverride
                : resolveDefaultCaptureMode(booking);
        return createPayment(bookingId, shopperReference, mintMerchantReference(),
                mode, amount, currency, coverage);
    }

    /**
     * Default capture mode is the {@link com.hotelops.core.product.vertical.VerticalStrategy#defaultCaptureMode}
     * of the booking's first active line (ordered by createdAt ascending). For an empty
     * folio (no active lines yet — edge case), fall back to {@link CaptureMode#MANUAL}.
     */
    private CaptureMode resolveDefaultCaptureMode(Booking booking) {
        return bookingLineRepository.findByBookingId(booking.getId()).stream()
                .filter(l -> l.getStatus() == BookingLineStatus.ACTIVE)
                .min(Comparator.comparing(BookingLine::getCreatedAt))
                .map(line -> strategyRegistry.forVertical(line.getVertical()).defaultCaptureMode())
                .orElse(CaptureMode.MANUAL);
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    /**
     * Create a new payment record (link/intent stage).
     * No posting at this stage — only a DB record is created. The caller mints
     * {@code merchantReference} via {@link #mintMerchantReference()} — it is
     * never accepted from the HTTP request body (SCH-031, API-008).
     */
    @Transactional
    public Payment createPayment(UUID bookingId, String shopperReference,
                                 String merchantReference, CaptureMode captureMode,
                                 long amountRequested, String currency) {
        return createPayment(bookingId, shopperReference, merchantReference, captureMode,
                amountRequested, currency, null);
    }

    /**
     * WHK-016 overload — persists optional scoped {@link PaymentLine} coverage rows alongside
     * the payment. Each covered line must belong to this booking; the coverage amounts must
     * sum exactly to {@code amountRequested} (Trap D — a partial/over-coverage is rejected
     * loudly with 400, never silently accepted).
     */
    @Transactional
    public Payment createPayment(UUID bookingId, String shopperReference,
                                 String merchantReference, CaptureMode captureMode,
                                 long amountRequested, String currency, List<LineCoverage> coverage) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new EntityNotFoundException("Booking not found: " + bookingId));
        Payment p = new Payment();
        p.setBooking(booking);
        p.setShopperReference(shopperReference);
        p.setMerchantReference(merchantReference);
        p.setCaptureMode(captureMode);
        p.setAmountRequested(amountRequested);
        p.setCurrency(currency != null ? currency : "GBP");
        paymentRepository.save(p);

        if (coverage != null && !coverage.isEmpty()) {
            persistCoverage(p, booking, amountRequested, coverage);
        }
        return p;
    }

    /**
     * WHK-016 — validate and persist scoped coverage rows. Trap D: coverage must sum to the
     * payment amount and every covered line must belong to this booking; both failures are
     * {@link IllegalArgumentException} → 400.
     */
    private void persistCoverage(Payment payment, Booking booking, long amountRequested,
                                 List<LineCoverage> coverage) {
        long sum = 0;
        for (LineCoverage c : coverage) {
            if (c.amount() <= 0) {
                throw new IllegalArgumentException(
                        "Coverage amount must be positive for line " + c.bookingLineId());
            }
            BookingLine line = bookingLineRepository.findById(c.bookingLineId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "BookingLine not found: " + c.bookingLineId()));
            if (!line.getBooking().getId().equals(booking.getId())) {
                throw new IllegalArgumentException(
                        "BookingLine " + c.bookingLineId() + " does not belong to booking " + booking.getId());
            }
            PaymentLine pl = new PaymentLine();
            pl.setPayment(payment);
            pl.setBookingLine(line);
            pl.setAmount(c.amount());
            pl.setCurrency(payment.getCurrency());
            paymentLineRepository.save(pl);
            sum += c.amount();
        }
        if (sum != amountRequested) {
            throw new IllegalArgumentException(
                    "Coverage amounts sum to " + sum + " but payment amount is " + amountRequested
                    + " (WHK-016: scoped coverage must fully cover the payment).");
        }
    }

    /**
     * PSP-006 tx2: stamp the PSP-minted {@code paymentLinkId} on the already-committed
     * {@code PENDING} payment, AFTER the outbound PSP-001 call has returned. A separate
     * public {@code @Transactional} method (invoked by {@link PaymentOrchestrator}, a
     * different bean) so the proxy applies and the HTTP call sits between tx1 and tx2 with
     * no open transaction. The {@code pspReference} / {@code amountAuthorised} are stamped
     * later still, on the AUTHORISATION webhook (WHK-006).
     */
    @Transactional
    public Payment stampPaymentLink(UUID paymentId, String paymentLinkId) {
        Payment p = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + paymentId));
        p.setPaymentLinkId(paymentLinkId);
        return paymentRepository.save(p);
    }

    // -------------------------------------------------------------------------
    // Capture — split request/settle (WHK-015)
    // -------------------------------------------------------------------------

    /**
     * API-010 request side: validate that a capture is permissible. Does NOT flip state,
     * does NOT enqueue an outbox event, does NOT post to the ledger. The CAPTURE webhook
     * (WHK-007) is the authoritative settlement signal — see {@link #settleCapture}.
     *
     * Validates INV-005 (single-capture) and SCH-032 (amount ≤ authorised). A {@code null}
     * captureAmount means "full capture" — resolved to {@code amountAuthorised}.
     *
     * @return the payment unmodified, so the 202 body reflects current truth.
     */
    @Transactional(readOnly = true)
    public Payment requestCapture(UUID paymentId, Long captureAmount) {
        Payment p = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + paymentId));
        long amount = (captureAmount != null) ? captureAmount : p.getAmountAuthorised();
        assertCapturable(p, amount);
        return p;
    }

    /**
     * WHK-007 settlement side: called by the inbound CAPTURE webhook. Flips status to
     * CAPTURED, stamps {@code amountCaptured}, enqueues the {@code PAYMENT_CAPTURED}
     * outbox event (the ledger processor posts per-line REVENUE), and recalculates the
     * booking roll-ups (INV-004).
     *
     * Re-runs INV-005 + SCH-032 as a defensive guard so a duplicated CAPTURE webhook
     * (one that escaped inbox dedupe) cannot double-post. The V2 unique index
     * {@code uq_posting_capture_line} is the final backstop.
     */
    @Transactional
    public Payment settleCapture(String merchantReference, long capturedAmount, String pspReference) {
        Payment p = paymentRepository.findByMerchantReference(merchantReference)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + merchantReference));
        assertCapturable(p, capturedAmount);

        if (pspReference != null && !pspReference.isBlank()) {
            p.setPspReference(pspReference);
        }
        p.setAmountCaptured(capturedAmount);
        p.setStatus(PaymentStatus.CAPTURED);
        paymentRepository.save(p);

        // INV-006: enqueue outbox event for the ledger processor (per-line REVENUE).
        enqueueOutboxEvent("PAYMENT_CAPTURED", "PAYMENT", p.getId(), Map.of(
                "paymentId",         p.getId().toString(),
                "bookingId",         p.getBooking().getId().toString(),
                "amountCaptured",    capturedAmount,
                "currency",          p.getCurrency(),
                "pspReference",      p.getPspReference() != null ? p.getPspReference() : "",
                "merchantReference", p.getMerchantReference()
        ));

        // INV-004: refresh booking roll-ups.
        bookingService.recalculateTotals(p.getBooking());
        bookingRepository.save(p.getBooking());

        return p;
    }

    /** WHK-010: mark payment CAPTURE_FAILED. No ledger effect. */
    @Transactional
    public Payment settleCaptureFailure(String merchantReference, String reason) {
        Payment p = paymentRepository.findByMerchantReference(merchantReference)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + merchantReference));
        p.setStatus(PaymentStatus.CAPTURE_FAILED);
        return paymentRepository.save(p);
    }

    // -------------------------------------------------------------------------
    // Cancellation — split request/settle (WHK-015)
    // -------------------------------------------------------------------------

    /** API-011 request side: validate that cancellation is permissible. State change happens on the webhook. */
    @Transactional(readOnly = true)
    public Payment requestCancellation(UUID paymentId) {
        Payment p = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + paymentId));
        if (p.getAmountCaptured() > 0) {
            throw new StateChangedException(
                    "Cannot cancel a payment that has already been captured: " + paymentId,
                    p.getStatus());
        }
        return p;
    }

    /**
     * WHK-008 settlement: webhook CANCELLATION of an uncaptured auth. No outbox, no posting.
     * Re-checks amountCaptured == 0 defensively (a CAPTURE could have raced in before).
     */
    @Transactional
    public Payment settleCancellation(String merchantReference) {
        Payment p = paymentRepository.findByMerchantReference(merchantReference)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + merchantReference));
        if (p.getAmountCaptured() > 0) {
            throw new StateChangedException(
                    "Cannot cancel a payment that has already been captured: " + p.getId(),
                    p.getStatus());
        }
        p.setStatus(PaymentStatus.CANCELLED);
        return paymentRepository.save(p);
    }

    /** WHK-011: AUTH_EXPIRY — only flips state if currently AUTHORISED; ignored when CAPTURED. */
    @Transactional
    public Payment settleAuthExpiry(String merchantReference) {
        Payment p = paymentRepository.findByMerchantReference(merchantReference)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + merchantReference));
        if (p.getStatus() == PaymentStatus.AUTHORISED) {
            p.setStatus(PaymentStatus.AUTH_EXPIRED);
            return paymentRepository.save(p);
        }
        return p;
    }

    // -------------------------------------------------------------------------
    // Authorisation (webhook-only; already split — unchanged)
    // -------------------------------------------------------------------------

    /**
     * WHK-006: AUTHORISATION webhook → stamp pspReference / amountAuthorised /
     * authExpiresAt; status PENDING → AUTHORISED. No posting (INV-006).
     */
    @Transactional
    public Payment recordAuthorisation(String merchantReference, String pspReference,
                                       long amountAuthorised,
                                       OffsetDateTime authExpiresAt) {
        Payment p = paymentRepository.findByMerchantReference(merchantReference)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found: " + merchantReference));
        p.setPspReference(pspReference);
        p.setAmountAuthorised(amountAuthorised);
        p.setAuthExpiresAt(authExpiresAt);
        p.setStatus(PaymentStatus.AUTHORISED);
        return paymentRepository.save(p);
    }

    // -------------------------------------------------------------------------
    // Refund (already split: createRefund = request, settleRefund = settle)
    // -------------------------------------------------------------------------

    /**
     * API-012 request side: validate refund and persist PENDING refund row.
     * Settlement (state flip + outbox + roll-ups) is {@link #settleRefund} (WHK-009).
     */
    @Transactional
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
     * WHK-009 settlement side (called when REFUND webhook arrives).
     * Enqueues REFUND_SETTLED outbox event → per-line REFUND_REVERSAL postings.
     */
    @Transactional
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

        bookingService.recalculateTotals(p.getBooking());
        bookingRepository.save(p.getBooking());

        return r;
    }

    /** WHK-010: mark refund REFUND_FAILED. Parent payment status untouched. */
    @Transactional
    public Refund settleRefundFailure(String refundMerchantReference, String reason) {
        Refund r = refundRepository.findByMerchantReference(refundMerchantReference)
                .orElseThrow(() -> new EntityNotFoundException("Refund not found: " + refundMerchantReference));
        r.setStatus(RefundStatus.REFUND_FAILED);
        return refundRepository.save(r);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Mint a server-side {@code merchantReference} (SCH-031, reconciliation anchor).
     * Mirrors {@code CustomerService.mintShopperReference()}.
     */
    public static String mintMerchantReference() {
        return MERCHANT_REF_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    private void assertCapturable(Payment p, long captureAmount) {
        if (p.getStatus() == PaymentStatus.CAPTURED
                || p.getStatus() == PaymentStatus.PARTIALLY_REFUNDED
                || p.getStatus() == PaymentStatus.REFUNDED) {
            throw new StateChangedException(
                    "Payment " + p.getId() + " has already been captured (INV-005); multi-capture rejected.",
                    p.getStatus());
        }
        if (p.getStatus() != PaymentStatus.AUTHORISED
                && !(p.getCaptureMode() == CaptureMode.IMMEDIATE
                     && p.getStatus() == PaymentStatus.PENDING)) {
            throw new StateChangedException(
                    "Payment " + p.getId() + " is not in a capturable state: " + p.getStatus(),
                    p.getStatus());
        }
        if (captureAmount > p.getAmountAuthorised() && p.getStatus() == PaymentStatus.AUTHORISED) {
            throw new StateChangedException(
                    "Capture amount " + captureAmount + " exceeds authorised amount " + p.getAmountAuthorised(),
                    p.getAmountAuthorised());
        }
    }

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
