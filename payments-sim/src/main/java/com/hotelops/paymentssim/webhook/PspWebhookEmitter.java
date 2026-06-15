package com.hotelops.paymentssim.webhook;

import com.hotelops.paymentssim.service.PreparedEvent;
import com.hotelops.paymentssim.service.PspTriggerService;
import org.springframework.stereotype.Component;

/**
 * 1C-a — closes the money loop on the <b>real, always-on</b> request endpoints
 * ({@code POST /v1/payments/{ref}/captures|cancellations|refunds}). After {@code core-api}
 * records its intent on the row (tx1, in the request controller), this bean drives the
 * matching settlement + webhook so {@code payments-sim} calls back without any
 * {@code /v1/test} step.
 *
 * <p><b>Reuse, not rewrite.</b> Settlement is the existing {@link PspTriggerService}
 * {@code prepare*} logic (row flip + {@code psp_event_sequence} stamp); delivery is the
 * existing {@link WebhookDispatcher}. There is no duplicated state-flip or envelope build,
 * so the emitted {@code idempotencyKey} ({@code pspRef:EVENTCODE:seq}) is byte-identical to
 * what the test seam produces.
 *
 * <p><b>Why a non-transactional bean</b> (mirrors {@code core-api}'s {@code PaymentOrchestrator}
 * and the {@code TestTriggerController} split):
 * <ul>
 *   <li>This bean is <b>not</b> {@code @Transactional}. It invokes {@code prepare*} <i>across</i>
 *       the bean boundary into {@link PspTriggerService}, so Spring's proxy applies and the
 *       settlement tx is honoured — not a self-invocation no-op (GAP-2 / Trap B).</li>
 *   <li>{@code prepare*}'s tx commits when the proxied call returns; only then does
 *       {@link WebhookDispatcher#dispatch} run, so the HTTP call never sits inside an open DB
 *       transaction (D3 / PSP-006 / Trap C).</li>
 * </ul>
 *
 * <p><b>Async only</b> ({@code sync=false}). The inline {@code sync=true} seam stays exclusive
 * to {@code @Profile("test")} {@code TestTriggerController}; promoting it onto an always-on
 * surface would move the WHK-015 test seam into prod (Trap A). Outbound failures are logged,
 * single-attempt, no-retry (PSP-007/008) — the {@code 202} already returned to {@code core-api}.
 *
 * <p><b>IMMEDIATE capture interaction</b> (Trap F): {@code core-api} only calls
 * {@code /captures} for {@code MANUAL} payments; an {@code IMMEDIATE} capture rides the
 * AUTHORISATION→CAPTURE chain in {@link PspTriggerService#prepareAuthorisation} (test-only
 * authorise), never a separate {@code /captures} request. So {@link #emitCapture} only ever
 * runs for MANUAL, and a {@code /captures} on an already-captured payment is rejected by the
 * intent tx before this bean is reached.
 */
@Component
public class PspWebhookEmitter {

    private final PspTriggerService triggerService;
    private final WebhookDispatcher dispatcher;

    public PspWebhookEmitter(PspTriggerService triggerService, WebhookDispatcher dispatcher) {
        this.triggerService = triggerService;
        this.dispatcher = dispatcher;
    }

    /** Settle the queued capture and fire the CAPTURE webhook asynchronously after commit. */
    public void emitCapture(String pspReference) {
        PreparedEvent prepared = triggerService.prepareCapture(pspReference);
        dispatcher.dispatch(prepared.envelope(), prepared.callbackUrl(), false);
    }

    /** Settle the queued cancellation and fire the CANCELLATION webhook asynchronously after commit. */
    public void emitCancellation(String pspReference) {
        PreparedEvent prepared = triggerService.prepareCancellation(pspReference);
        dispatcher.dispatch(prepared.envelope(), prepared.callbackUrl(), false);
    }

    /** Settle the PENDING refund and fire the REFUND webhook asynchronously after commit. */
    public void emitRefund(String refundMerchantReference) {
        PreparedEvent prepared = triggerService.prepareRefund(refundMerchantReference);
        dispatcher.dispatch(prepared.envelope(), prepared.callbackUrl(), false);
    }
}
