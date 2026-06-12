package com.hotelops.paymentssim.service;

import com.hotelops.paymentssim.common.error.AmountExceededException;
import com.hotelops.paymentssim.common.error.DuplicateAnchorException;
import com.hotelops.paymentssim.common.error.InvalidStateTransitionException;
import com.hotelops.paymentssim.common.reference.ReferenceMinter;
import com.hotelops.paymentssim.domain.CaptureMode;
import com.hotelops.paymentssim.domain.PspPayment;
import com.hotelops.paymentssim.domain.PspPaymentRepository;
import com.hotelops.paymentssim.domain.PspPaymentStatus;
import com.hotelops.paymentssim.web.dto.CreatePaymentLinkRequest;
import com.hotelops.paymentssim.web.dto.PaymentLinkResponse;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PSP-001 / PSP-002 / PSP-003 — request side. 1B persists intent only; the status flip to
 * {@code CAPTURED}/{@code CANCELLED} happens on the webhook in 1C.
 */
@Service
public class PspPaymentService {

    private final PspPaymentRepository payments;
    private final ReferenceMinter minter;
    private final String hostedBaseUrl;
    private final String defaultCallbackUrl;

    public PspPaymentService(
            PspPaymentRepository payments,
            ReferenceMinter minter,
            @Value("${psp-sim.hosted-base-url:http://payments-sim:8081}") String hostedBaseUrl,
            @Value("${psp-sim.core-api-webhook-url:http://core-api:8080/webhooks/psp}") String defaultCallbackUrl) {
        this.payments = payments;
        this.minter = minter;
        this.hostedBaseUrl = hostedBaseUrl;
        this.defaultCallbackUrl = defaultCallbackUrl;
    }

    /** PSP-001 — create payment link; persist PENDING; mint PL-. */
    @Transactional
    public PaymentLinkResponse createLink(CreatePaymentLinkRequest req) {
        payments.findByMerchantReference(req.merchantReference()).ifPresent(existing -> {
            throw new DuplicateAnchorException(
                    "DUPLICATE_MERCHANT_REFERENCE",
                    "merchantReference already used: " + req.merchantReference());
        });

        PspPayment p = new PspPayment();
        p.setMerchantReference(req.merchantReference());
        p.setShopperReference(req.shopperReference());
        p.setAmountRequested(req.amount());
        p.setCurrency(req.currency());
        p.setCaptureMode(req.captureMode() == null ? CaptureMode.MANUAL : req.captureMode());
        p.setStatus(PspPaymentStatus.PENDING);
        p.setCallbackUrl(req.callbackUrl() == null || req.callbackUrl().isBlank()
                ? defaultCallbackUrl
                : req.callbackUrl());
        p.setPaymentLinkId(minter.mintPaymentLinkId());

        PspPayment saved = payments.save(p);
        return new PaymentLinkResponse(
                saved.getPaymentLinkId(),
                saved.getMerchantReference(),
                saved.getShopperReference(),
                saved.getAmountRequested(),
                saved.getCurrency(),
                saved.getStatus(),
                hostedBaseUrl + "/checkout/" + saved.getPaymentLinkId());
    }

    /** PSP-002 — record capture intent on the row; webhook is 1C. */
    @Transactional
    public CaptureIntent requestCapture(String pspReference, Long requestedAmount) {
        PspPayment p = payments.findByPspReference(pspReference)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Unknown pspReference: " + pspReference));

        if (p.getStatus() != PspPaymentStatus.AUTHORISED) {
            throw new InvalidStateTransitionException(
                    "INVALID_STATE",
                    "Payment " + pspReference + " is not capturable in state " + p.getStatus());
        }
        if (p.getPendingCaptureAmount() != null || p.getAmountCaptured() > 0) {
            // INV-005 — single capture per auth. A queued capture counts the same as
            // a settled one for the rejection.
            throw new InvalidStateTransitionException(
                    "ALREADY_CAPTURED",
                    "Payment " + pspReference + " already has a capture (INV-005)");
        }
        if (p.isCancellationPending()) {
            throw new InvalidStateTransitionException(
                    "INVALID_STATE",
                    "Payment " + pspReference + " has a queued cancellation");
        }

        long amount = requestedAmount == null ? p.getAmountAuthorised() : requestedAmount;
        if (amount > p.getAmountAuthorised()) {
            throw new AmountExceededException(
                    "AMOUNT_EXCEEDS_AUTHORISED",
                    "Capture amount " + amount + " exceeds authorised " + p.getAmountAuthorised());
        }

        p.setPendingCaptureAmount(amount);
        return new CaptureIntent(p.getPspReference(), p.getMerchantReference(), amount);
    }

    /** PSP-003 — record cancellation intent; webhook is 1C. */
    @Transactional
    public CancellationIntent requestCancellation(String pspReference) {
        PspPayment p = payments.findByPspReference(pspReference)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Unknown pspReference: " + pspReference));

        if (p.getStatus() != PspPaymentStatus.AUTHORISED) {
            throw new InvalidStateTransitionException(
                    "CANCEL_NOT_PERMITTED",
                    "Payment " + pspReference + " cannot be cancelled in state " + p.getStatus());
        }
        if (p.getAmountCaptured() > 0 || p.getPendingCaptureAmount() != null) {
            throw new InvalidStateTransitionException(
                    "CANCEL_NOT_PERMITTED",
                    "Payment " + pspReference + " has captured/queued-capture amount; cannot cancel");
        }
        if (p.isCancellationPending()) {
            throw new InvalidStateTransitionException(
                    "CANCEL_ALREADY_REQUESTED",
                    "Payment " + pspReference + " already has a queued cancellation");
        }

        p.setCancellationPending(true);
        return new CancellationIntent(p.getPspReference(), p.getMerchantReference());
    }

    public record CaptureIntent(String pspReference, String merchantReference, long amount) {}

    public record CancellationIntent(String pspReference, String merchantReference) {}
}
