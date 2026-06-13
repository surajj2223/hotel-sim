package com.hotelops.core.payment;

import com.hotelops.core.common.enums.CaptureMode;
import com.hotelops.core.payment.psp.PspGateway;
import com.hotelops.core.payment.psp.dto.PspCreateLinkRequest;
import com.hotelops.core.payment.psp.dto.PspPaymentLinkResponse;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * PSP-006 — the non-transactional sequencer that drives the outbound
 * {@code core-api → payments-sim} calls with the HTTP round-trip kept strictly OUTSIDE any
 * open DB transaction:
 *
 * <pre>
 *   [tx1] PaymentService.createPaymentLink  → commit   (PENDING row persisted)
 *   [net] PspGateway.createLink                         (no open tx)
 *   [tx2] PaymentService.stampPaymentLink   → commit   (stamp paymentLinkId)
 * </pre>
 *
 * <p>This bean is <b>not</b> {@code @Transactional}. It invokes the proxied public methods
 * on {@link PaymentService} (a different bean), so each opens and commits its own
 * transaction — the GAP-2 split applied to the outbound seam. A failure of the network step
 * raises {@link com.hotelops.core.payment.psp.PspGatewayException}; tx2 never runs, the row
 * stays {@code PENDING}, and nothing is retried (PSP-007 / PSP-008).
 *
 * <p>Capture / cancel / refund have no tx2: their authoritative state change lands on the
 * inbound webhook (WHK-007/008/009). The request side only validates (and, for refund,
 * persists a {@code PENDING} child) before the PSP call.
 */
@Service
public class PaymentOrchestrator {

    private final PaymentService paymentService;
    private final PspGateway pspGateway;

    public PaymentOrchestrator(PaymentService paymentService, PspGateway pspGateway) {
        this.paymentService = paymentService;
        this.pspGateway = pspGateway;
    }

    /**
     * API-008 — create a payment link. tx1 persists the {@code PENDING} payment (minting
     * {@code merchantReference}); the PSP-001 call mints {@code paymentLinkId}; tx2 stamps it.
     */
    public Payment createPaymentLink(UUID bookingId, long amount, String currency,
                                     CaptureMode captureModeOverride) {
        Payment pending = paymentService.createPaymentLink(bookingId, amount, currency, captureModeOverride);

        // callbackUrl omitted → payments-sim uses its configured CORE_API_WEBHOOK_URL (PSP-016).
        PspPaymentLinkResponse psp = pspGateway.createLink(new PspCreateLinkRequest(
                pending.getMerchantReference(),
                pending.getShopperReference(),
                pending.getAmountRequested(),
                pending.getCurrency(),
                pending.getCaptureMode(),
                null));

        return paymentService.stampPaymentLink(pending.getId(), psp.paymentLinkId());
    }

    /**
     * API-010 — request capture. tx1 validates (INV-005 / SCH-032); the PSP-002 call queues
     * the {@code CAPTURE} webhook. State flips on that webhook, so the returned payment is
     * unchanged (202).
     */
    public Payment requestCapture(UUID paymentId, Long captureAmount) {
        Payment payment = paymentService.requestCapture(paymentId, captureAmount);
        pspGateway.requestCapture(payment.getPspReference(), captureAmount);
        return payment;
    }

    /** API-011 — request cancellation. tx1 validates; PSP-003 queues the CANCELLATION webhook. */
    public Payment requestCancellation(UUID paymentId) {
        Payment payment = paymentService.requestCancellation(paymentId);
        pspGateway.requestCancellation(payment.getPspReference());
        return payment;
    }

    /**
     * API-012 — request refund. tx1 persists the {@code PENDING} child refund (minting its
     * {@code merchantReference}, chaining {@code originalReference} to the parent
     * {@code pspReference}); PSP-004 queues the {@code REFUND} webhook. The refund's own
     * {@code pspReference} is stamped later, on that webhook (WHK-009).
     */
    public Refund requestRefund(UUID paymentId, long amount, String reason) {
        Refund refund = paymentService.createRefund(
                paymentId, amount, PaymentService.mintMerchantReference(), reason);
        pspGateway.requestRefund(
                refund.getOriginalReference(), amount, refund.getMerchantReference(), reason);
        return refund;
    }
}
