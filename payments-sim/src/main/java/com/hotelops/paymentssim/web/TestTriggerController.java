package com.hotelops.paymentssim.web;

import com.hotelops.paymentssim.domain.PspPaymentStatus;
import com.hotelops.paymentssim.service.PreparedEvent;
import com.hotelops.paymentssim.service.PspTriggerService;
import com.hotelops.paymentssim.web.dto.ApiError;
import com.hotelops.paymentssim.web.dto.AuthoriseTriggerRequest;
import com.hotelops.paymentssim.web.dto.AuthoriseTriggerResponse;
import com.hotelops.paymentssim.web.dto.TriggerAckResponse;
import com.hotelops.paymentssim.webhook.WebhookDispatcher;
import com.hotelops.paymentssim.webhook.WebhookSender;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PSP-013 / PSP-015 / WAVE0_05 §5–6 — operator/test-only triggers that drive the four
 * webhook events {@code payments-sim} emits.
 *
 * <p><b>{@code @Profile("test")} is the seam-unreachable-in-prod guarantee</b>
 * (CLAUDE.md / WHK-015 / PSP-015): outside the {@code test} profile this bean is not
 * registered, so all {@code /v1/test/...} routes return 404. Compose never sets
 * {@code SPRING_PROFILES_ACTIVE=test} for {@code payments-sim}.
 *
 * <p>The controller is the non-transactional caller (mirrors the GAP-2 / PSP-006 split):
 * it invokes the {@link PspTriggerService} proxied method (tx commits), then hands the
 * envelope to the {@link WebhookDispatcher} — the HTTP call to {@code core-api} never
 * sits inside an open DB transaction.
 *
 * <p>{@code ?sync=true} on every endpoint switches dispatch from executor to inline,
 * so end-to-end tests can assert against {@code core-api}'s persisted state without
 * sleeps. Default is async.
 */
@RestController
@Profile("test")
@RequestMapping("/v1/test")
public class TestTriggerController {

    private final PspTriggerService triggerService;
    private final WebhookDispatcher dispatcher;

    public TestTriggerController(PspTriggerService triggerService, WebhookDispatcher dispatcher) {
        this.triggerService = triggerService;
        this.dispatcher = dispatcher;
    }

    // -------------------------------------------------------------------------
    // PSP-013 — authorise a PENDING payment link
    // -------------------------------------------------------------------------

    @PostMapping("/payment-links/{paymentLinkId}/authorise")
    public ResponseEntity<?> authorise(
            @PathVariable String paymentLinkId,
            @RequestParam(name = "sync", defaultValue = "false") boolean sync,
            @RequestBody(required = false) AuthoriseTriggerRequest body) {
        Long override = body == null ? null : body.amount();
        // IMMEDIATE returns two events (AUTHORISATION then CAPTURE); MANUAL returns one.
        // Dispatch them in order through the same dispatcher — sync delivers inline,
        // async submits to the single-thread executor which preserves order — and
        // fail-loud on the first failed delivery.
        java.util.List<PreparedEvent> events = triggerService.prepareAuthorisation(paymentLinkId, override);
        for (PreparedEvent event : events) {
            var dispatchOutcome = dispatchOrFailLoud(event, sync);
            if (dispatchOutcome != null) return dispatchOutcome;
        }
        PreparedEvent auth = events.get(0);
        PspPaymentStatus finalStatus = events.size() > 1
                ? PspPaymentStatus.CAPTURED       // IMMEDIATE: auth-and-capture-together
                : PspPaymentStatus.AUTHORISED;     // MANUAL: awaiting a separate capture
        AuthoriseTriggerResponse resp = new AuthoriseTriggerResponse(
                paymentLinkId,
                auth.envelope().pspReference(),
                auth.envelope().amount(),
                finalStatus);
        return ResponseEntity.status(sync ? HttpStatus.OK : HttpStatus.ACCEPTED).body(resp);
    }

    // -------------------------------------------------------------------------
    // PSP-015 §5.2 — drive the queued CAPTURE webhook
    // -------------------------------------------------------------------------

    @PostMapping("/payments/{pspReference}/capture")
    public ResponseEntity<?> capture(
            @PathVariable String pspReference,
            @RequestParam(name = "sync", defaultValue = "false") boolean sync) {
        PreparedEvent prepared = triggerService.prepareCapture(pspReference);
        return ackOrFailLoud(prepared, sync, "CAPTURE");
    }

    // -------------------------------------------------------------------------
    // PSP-015 §5.2 — drive the queued CANCELLATION webhook
    // -------------------------------------------------------------------------

    @PostMapping("/payments/{pspReference}/cancel")
    public ResponseEntity<?> cancel(
            @PathVariable String pspReference,
            @RequestParam(name = "sync", defaultValue = "false") boolean sync) {
        PreparedEvent prepared = triggerService.prepareCancellation(pspReference);
        return ackOrFailLoud(prepared, sync, "CANCELLATION");
    }

    // -------------------------------------------------------------------------
    // PSP-015 §5.2 — settle a PENDING refund (drive REFUND webhook)
    // -------------------------------------------------------------------------

    @PostMapping("/refunds/{refundMerchantReference}/settle")
    public ResponseEntity<?> settleRefund(
            @PathVariable String refundMerchantReference,
            @RequestParam(name = "sync", defaultValue = "false") boolean sync) {
        PreparedEvent prepared = triggerService.prepareRefund(refundMerchantReference);
        return ackOrFailLoud(prepared, sync, "REFUND");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Sync-mode failures surface as 502 so tests can pin them down. Async returns null
     * (success-by-default; failures are logged by {@link WebhookSender}).
     */
    private ResponseEntity<?> dispatchOrFailLoud(PreparedEvent prepared, boolean sync) {
        WebhookSender.DispatchResult result = dispatcher.dispatch(
                prepared.envelope(), prepared.callbackUrl(), sync);
        if (sync && result != null && !result.delivered()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ApiError(
                            "WEBHOOK_DELIVERY_FAILED",
                            "core-api returned " + result.statusCode() + ": " + result.body()));
        }
        return null;
    }

    private ResponseEntity<?> ackOrFailLoud(PreparedEvent prepared, boolean sync, String eventCode) {
        var failure = dispatchOrFailLoud(prepared, sync);
        if (failure != null) return failure;
        return ResponseEntity.status(sync ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(new TriggerAckResponse(prepared.envelope().pspReference(), eventCode));
    }
}
