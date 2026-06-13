package com.hotelops.paymentssim.webhook;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * Routes webhook delivery: either inline (sync seam, PSP-015) or via a small executor
 * (production async path, PSP-016). <b>Both paths call the same {@link WebhookSender}
 * code</b> — there is no parallel dispatcher; the only difference is who runs the call.
 * Rationale: WAVE0_05 §6 — a dedicated sync endpoint would duplicate the dispatcher
 * and risk drift.
 */
@Component
public class WebhookDispatcher {

    private final WebhookSender sender;
    private final ExecutorService executor;

    public WebhookDispatcher(WebhookSender sender) {
        this.sender = sender;
        this.executor = Executors.newSingleThreadExecutor(daemonFactory());
    }

    /**
     * @param sync {@code true} = inline; the returned {@link WebhookSender.DispatchResult}
     *             reflects {@code core-api}'s actual response so the test-only sync seam
     *             can map a non-2xx to a 502 trigger response. {@code false} = submit to
     *             the executor and return {@code null} immediately.
     */
    public WebhookSender.DispatchResult dispatch(WebhookEnvelope envelope, String callbackUrl, boolean sync) {
        if (sync) {
            return sender.send(envelope, callbackUrl);
        }
        executor.submit(() -> sender.send(envelope, callbackUrl));
        return null;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    private static ThreadFactory daemonFactory() {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "psp-webhook-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
