package com.hotelops.core.payment.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** SCH-070, SCH-071 */
public interface WebhookInboxRepository extends JpaRepository<WebhookInbox, UUID> {

    /** SCH-071 — dedupe check; present == already processed. */
    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<WebhookInbox> findByIdempotencyKey(String idempotencyKey);

    /** Match inbound to payment by merchantReference (SCH-070). */
    java.util.List<WebhookInbox> findByMerchantReference(String merchantReference);
}
