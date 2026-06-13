package com.hotelops.paymentssim.service;

import com.hotelops.paymentssim.webhook.WebhookEnvelope;

/**
 * Returned by every {@link PspTriggerService} method: a committed envelope ready to
 * dispatch, paired with the destination callback URL. The transactional service
 * commits the row mutation + seq stamp; the non-transactional controller then calls
 * {@link com.hotelops.paymentssim.webhook.WebhookDispatcher#dispatch} — keeping the
 * HTTP call strictly outside any open DB transaction.
 */
public record PreparedEvent(WebhookEnvelope envelope, String callbackUrl) {
}
