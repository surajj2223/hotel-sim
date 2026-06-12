package com.hotelops.paymentssim.service;

import com.hotelops.paymentssim.common.error.AmountExceededException;
import com.hotelops.paymentssim.common.error.DuplicateAnchorException;
import com.hotelops.paymentssim.common.reference.ReferenceMinter;
import com.hotelops.paymentssim.domain.PspPayment;
import com.hotelops.paymentssim.domain.PspPaymentRepository;
import com.hotelops.paymentssim.domain.PspRefund;
import com.hotelops.paymentssim.domain.PspRefundRepository;
import com.hotelops.paymentssim.domain.PspRefundStatus;
import com.hotelops.paymentssim.web.dto.RefundAckResponse;
import com.hotelops.paymentssim.web.dto.RefundRequest;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PSP-004 — request side of refund. Persists a PENDING psp_refund row and mints a
 * distinct refund pspReference (PSP-010); the REFUND webhook lands in 1C.
 *
 * Row-locks the parent payment so concurrent refund requests cannot both pass the
 * "captured − refunded − sum(pending)" check (§6.4 plan flag).
 */
@Service
public class PspRefundService {

    private final PspPaymentRepository payments;
    private final PspRefundRepository refunds;
    private final ReferenceMinter minter;

    public PspRefundService(PspPaymentRepository payments,
                            PspRefundRepository refunds,
                            ReferenceMinter minter) {
        this.payments = payments;
        this.refunds = refunds;
        this.minter = minter;
    }

    @Transactional
    public RefundAckResponse requestRefund(String pspReference, RefundRequest req) {
        PspPayment parent = payments.lockByPspReference(pspReference)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Unknown pspReference: " + pspReference));

        refunds.findByRefundMerchantReference(req.refundMerchantReference()).ifPresent(existing -> {
            throw new DuplicateAnchorException(
                    "DUPLICATE_REFUND_MERCHANT_REFERENCE",
                    "refundMerchantReference already used: " + req.refundMerchantReference());
        });

        long sumPending = refunds.sumPendingByOriginalReference(pspReference);
        long capturable = parent.getAmountCaptured() - parent.getAmountRefunded() - sumPending;
        if (req.amount() > capturable) {
            throw new AmountExceededException(
                    "AMOUNT_EXCEEDS_CAPTURABLE",
                    "Refund amount " + req.amount() + " exceeds capturable " + capturable);
        }

        PspRefund r = new PspRefund();
        r.setRefundMerchantReference(req.refundMerchantReference());
        r.setPspReference(minter.mintPspReference());
        r.setOriginalReference(parent.getPspReference());
        r.setAmount(req.amount());
        r.setCurrency(parent.getCurrency());
        r.setStatus(PspRefundStatus.PENDING);
        r.setReason(req.reason());

        PspRefund saved = refunds.save(r);
        return new RefundAckResponse(
                saved.getPspReference(),
                saved.getOriginalReference(),
                saved.getRefundMerchantReference(),
                saved.getAmount(),
                saved.getCurrency(),
                RefundAckResponse.PENDING_REFUND);
    }
}
