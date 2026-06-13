package com.hotelops.paymentssim.service;

import com.hotelops.paymentssim.common.error.InvalidStateTransitionException;
import com.hotelops.paymentssim.common.reference.ReferenceMinter;
import com.hotelops.paymentssim.domain.PspEventCode;
import com.hotelops.paymentssim.domain.PspEventSequence;
import com.hotelops.paymentssim.domain.PspEventSequenceId;
import com.hotelops.paymentssim.domain.PspEventSequenceRepository;
import com.hotelops.paymentssim.domain.PspPayment;
import com.hotelops.paymentssim.domain.PspPaymentRepository;
import com.hotelops.paymentssim.domain.PspPaymentStatus;
import com.hotelops.paymentssim.domain.PspRefund;
import com.hotelops.paymentssim.domain.PspRefundRepository;
import com.hotelops.paymentssim.domain.PspRefundStatus;
import com.hotelops.paymentssim.webhook.WebhookEnvelope;
import jakarta.persistence.EntityNotFoundException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PSP-013 / WAVE0_05 §5 — the operator/test-facing surface that drives state
 * transitions on the persisted PSP row and produces the matching webhook envelope.
 *
 * <p>Discipline: each public method is the unit of work; it (a) re-validates current
 * state, (b) flips the row, (c) finds-or-creates the {@code psp_event_sequence} row
 * (PSP-011), (d) returns a {@link PreparedEvent}. The controller dispatches the
 * envelope AFTER the transaction commits — the HTTP call never sits inside an open
 * DB transaction (mirrors the PSP-006 discipline on the {@code core-api} side).
 *
 * <p>Find-or-create on {@code psp_event_sequence} is the determinism mechanism behind
 * PSP-011 / WHK-003: redelivering the same logical event reuses the row, so the
 * emitted {@code idempotencyKey} stays identical, which is what makes {@code core-api}'s
 * WHK-005 inbox dedupe correct.
 */
@Service
public class PspTriggerService {

    /** AUTHORISATION webhook expiry — informational only; no row column tracks it. */
    private static final long AUTH_TTL_HOURS = 24 * 7;

    private final PspPaymentRepository payments;
    private final PspRefundRepository refunds;
    private final PspEventSequenceRepository sequences;
    private final ReferenceMinter minter;

    public PspTriggerService(PspPaymentRepository payments,
                             PspRefundRepository refunds,
                             PspEventSequenceRepository sequences,
                             ReferenceMinter minter) {
        this.payments = payments;
        this.refunds = refunds;
        this.sequences = sequences;
        this.minter = minter;
    }

    // -------------------------------------------------------------------------
    // PSP-013 — authorise
    // -------------------------------------------------------------------------

    @Transactional
    public PreparedEvent prepareAuthorisation(String paymentLinkId, Long overrideAmount) {
        PspPayment p = payments.findByPaymentLinkId(paymentLinkId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Unknown paymentLinkId: " + paymentLinkId));

        if (p.getStatus() != PspPaymentStatus.PENDING) {
            throw new InvalidStateTransitionException(
                    "ALREADY_AUTHORISED",
                    "Payment link " + paymentLinkId + " is not PENDING (state " + p.getStatus() + ")");
        }

        long amount = (overrideAmount != null) ? overrideAmount : p.getAmountRequested();
        if (amount <= 0 || amount > p.getAmountRequested()) {
            throw new InvalidStateTransitionException(
                    "INVALID_AUTHORISATION_AMOUNT",
                    "Authorisation amount " + amount + " is not in (0, " + p.getAmountRequested() + "]");
        }

        p.setPspReference(minter.mintPspReference());
        p.setAmountAuthorised(amount);
        p.setStatus(PspPaymentStatus.AUTHORISED);

        int seq = findOrCreateSeq(p.getPspReference(), PspEventCode.AUTHORISATION);
        OffsetDateTime now = OffsetDateTime.now();
        WebhookEnvelope envelope = new WebhookEnvelope(
                "evt_" + UUID.randomUUID(),
                PspEventCode.AUTHORISATION,
                idempotencyKey(p.getPspReference(), PspEventCode.AUTHORISATION, seq),
                p.getMerchantReference(),
                p.getPspReference(),
                amount,
                p.getCurrency(),
                now,
                true,
                now.plus(AUTH_TTL_HOURS, ChronoUnit.HOURS),
                null, null, null);
        return new PreparedEvent(envelope, p.getCallbackUrl());
    }

    // -------------------------------------------------------------------------
    // PSP-015 §5.2 — settle capture (drives the pre-queued CAPTURE webhook)
    // -------------------------------------------------------------------------

    @Transactional
    public PreparedEvent prepareCapture(String pspReference) {
        PspPayment p = lookupByPspRef(pspReference);
        Long pending = p.getPendingCaptureAmount();
        if (pending == null) {
            throw new InvalidStateTransitionException(
                    "NO_CAPTURE_QUEUED",
                    "Payment " + pspReference + " has no queued capture; PSP-002 first");
        }
        if (p.getStatus() != PspPaymentStatus.AUTHORISED) {
            throw new InvalidStateTransitionException(
                    "INVALID_STATE",
                    "Payment " + pspReference + " is not AUTHORISED (state " + p.getStatus() + ")");
        }

        p.setAmountCaptured(pending);
        p.setPendingCaptureAmount(null);
        p.setStatus(PspPaymentStatus.CAPTURED);

        int seq = findOrCreateSeq(p.getPspReference(), PspEventCode.CAPTURE);
        OffsetDateTime now = OffsetDateTime.now();
        WebhookEnvelope envelope = new WebhookEnvelope(
                "evt_" + UUID.randomUUID(),
                PspEventCode.CAPTURE,
                idempotencyKey(p.getPspReference(), PspEventCode.CAPTURE, seq),
                p.getMerchantReference(),
                p.getPspReference(),
                pending,
                p.getCurrency(),
                now,
                true,
                null, null, null, null);
        return new PreparedEvent(envelope, p.getCallbackUrl());
    }

    // -------------------------------------------------------------------------
    // PSP-015 §5.2 — settle cancellation
    // -------------------------------------------------------------------------

    @Transactional
    public PreparedEvent prepareCancellation(String pspReference) {
        PspPayment p = lookupByPspRef(pspReference);
        if (!p.isCancellationPending()) {
            throw new InvalidStateTransitionException(
                    "NO_CANCELLATION_QUEUED",
                    "Payment " + pspReference + " has no queued cancellation; PSP-003 first");
        }
        if (p.getAmountCaptured() > 0) {
            throw new InvalidStateTransitionException(
                    "CANNOT_CANCEL_CAPTURED",
                    "Payment " + pspReference + " has captured amount; cannot cancel");
        }

        p.setCancellationPending(false);
        p.setStatus(PspPaymentStatus.CANCELLED);

        int seq = findOrCreateSeq(p.getPspReference(), PspEventCode.CANCELLATION);
        OffsetDateTime now = OffsetDateTime.now();
        WebhookEnvelope envelope = new WebhookEnvelope(
                "evt_" + UUID.randomUUID(),
                PspEventCode.CANCELLATION,
                idempotencyKey(p.getPspReference(), PspEventCode.CANCELLATION, seq),
                p.getMerchantReference(),
                p.getPspReference(),
                0L,                                          // §3: CANCELLATION amount = 0
                p.getCurrency(),
                now,
                true,
                null, null, null, null);
        return new PreparedEvent(envelope, p.getCallbackUrl());
    }

    // -------------------------------------------------------------------------
    // PSP-015 §5.2 — settle refund (drives the REFUND webhook for an existing PENDING psp_refund)
    // -------------------------------------------------------------------------

    @Transactional
    public PreparedEvent prepareRefund(String refundMerchantReference) {
        PspRefund r = refunds.findByRefundMerchantReference(refundMerchantReference)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Unknown refundMerchantReference: " + refundMerchantReference));

        if (r.getStatus() != PspRefundStatus.PENDING) {
            throw new InvalidStateTransitionException(
                    "REFUND_NOT_PENDING",
                    "Refund " + refundMerchantReference + " is not PENDING (state " + r.getStatus() + ")");
        }

        PspPayment parent = payments.lockByPspReference(r.getOriginalReference())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Unknown originalReference: " + r.getOriginalReference()));

        long newRefunded = parent.getAmountRefunded() + r.getAmount();
        if (newRefunded > parent.getAmountCaptured()) {
            throw new InvalidStateTransitionException(
                    "REFUND_EXCEEDS_CAPTURED",
                    "Refund settlement would push refunded > captured");
        }
        parent.setAmountRefunded(newRefunded);
        parent.setStatus(newRefunded == parent.getAmountCaptured()
                ? PspPaymentStatus.REFUNDED
                : PspPaymentStatus.PARTIALLY_REFUNDED);
        r.setStatus(PspRefundStatus.REFUNDED);

        int seq = findOrCreateSeq(r.getPspReference(), PspEventCode.REFUND);
        OffsetDateTime now = OffsetDateTime.now();
        WebhookEnvelope envelope = new WebhookEnvelope(
                "evt_" + UUID.randomUUID(),
                PspEventCode.REFUND,
                idempotencyKey(r.getPspReference(), PspEventCode.REFUND, seq),
                parent.getMerchantReference(),                // parent merchant ref (anchor)
                r.getPspReference(),                          // refund's own distinct psp ref
                r.getAmount(),
                r.getCurrency(),
                now,
                true,
                null,
                null,
                parent.getPspReference(),                     // originalReference
                r.getRefundMerchantReference());
        return new PreparedEvent(envelope, parent.getCallbackUrl());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PspPayment lookupByPspRef(String pspReference) {
        return payments.findByPspReference(pspReference)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Unknown pspReference: " + pspReference));
    }

    /**
     * PSP-011 — find-or-create: a redelivery hits the existing row and reuses {@code seq}
     * (no increment). The seq column starts at 1 by default; on the rare concurrent-insert
     * race, the DB unique constraint surfaces a {@link DataIntegrityViolationException}
     * and we re-read.
     */
    int findOrCreateSeq(String pspReference, PspEventCode code) {
        var id = new PspEventSequenceId(pspReference, code.name());
        return sequences.findById(id).map(PspEventSequence::getSeq).orElseGet(() -> {
            PspEventSequence row = new PspEventSequence();
            row.setPspReference(pspReference);
            row.setEventCode(code.name());
            row.setSeq(1);
            row.setLastEmittedAt(OffsetDateTime.now());
            try {
                return sequences.saveAndFlush(row).getSeq();
            } catch (DataIntegrityViolationException race) {
                return sequences.findById(id).orElseThrow().getSeq();
            }
        });
    }

    static String idempotencyKey(String pspReference, PspEventCode code, int seq) {
        return pspReference + ":" + code.name() + ":" + seq;
    }
}
