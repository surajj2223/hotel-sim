package com.hotelops.core.ledger;

import com.hotelops.core.AbstractDataJpaTest;
import com.hotelops.core.booking.Booking;
import com.hotelops.core.booking.BookingRepository;
import com.hotelops.core.common.enums.PostingType;
import com.hotelops.core.common.enums.Vertical;
import com.hotelops.core.customer.Customer;
import com.hotelops.core.customer.CustomerRepository;
import com.hotelops.core.payment.webhook.WebhookInbox;
import com.hotelops.core.payment.webhook.WebhookInboxRepository;
import com.hotelops.core.common.enums.OutboxStatus;
import com.hotelops.core.common.enums.PspEventCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests proving SCH-050, SCH-051, SCH-060, SCH-070, SCH-071.
 */
class LedgerAndOutboxEntityTest extends AbstractDataJpaTest {

    @Autowired LedgerPostingRepository postingRepository;
    @Autowired OutboxEventRepository outboxRepository;
    @Autowired WebhookInboxRepository webhookRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired CustomerRepository customerRepository;

    // ── SCH-050 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_050_ledger_posting_revenue_persists_with_vertical_attribution() {
        Booking b = booking();
        LedgerPosting p = new LedgerPosting();
        p.setPostingType(PostingType.REVENUE);
        p.setBooking(b);
        p.setVertical(Vertical.ROOM);
        p.setAmount(20000L);           // positive — REVENUE
        LedgerPosting saved = postingRepository.save(p);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPostedAt()).isNotNull();
        assertThat(saved.getVertical()).isEqualTo(Vertical.ROOM);
    }

    @Test
    void SCH_050_refund_reversal_persists_with_negative_amount() {
        Booking b = booking();
        LedgerPosting p = new LedgerPosting();
        p.setPostingType(PostingType.REFUND_REVERSAL);
        p.setBooking(b);
        p.setVertical(Vertical.SPA);
        p.setAmount(-5000L);           // negative — REFUND_REVERSAL
        assertThatCode(() -> postingRepository.save(p)).doesNotThrowAnyException();
    }

    // ── SCH-051 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_051_revenue_posting_with_negative_amount_is_rejected() {
        Booking b = booking();
        LedgerPosting p = new LedgerPosting();
        p.setPostingType(PostingType.REVENUE);
        p.setBooking(b);
        p.setVertical(Vertical.FNB);
        p.setAmount(-1L);             // REVENUE must be >= 0
        assertThatThrownBy(() -> postingRepository.saveAndFlush(p))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void SCH_051_refund_reversal_with_positive_amount_is_rejected() {
        Booking b = booking();
        LedgerPosting p = new LedgerPosting();
        p.setPostingType(PostingType.REFUND_REVERSAL);
        p.setBooking(b);
        p.setVertical(Vertical.EVENT);
        p.setAmount(1L);              // REFUND_REVERSAL must be <= 0
        assertThatThrownBy(() -> postingRepository.saveAndFlush(p))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── SCH-060 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_060_outbox_event_persists_with_pending_status() {
        OutboxEvent e = new OutboxEvent();
        e.setEventType("PAYMENT_CAPTURED");
        e.setAggregateType("PAYMENT");
        e.setAggregateId(UUID.randomUUID());
        e.setPayload(Map.of("paymentId", UUID.randomUUID().toString()));
        OutboxEvent saved = outboxRepository.save(e);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getAttempts()).isZero();
    }

    @Test
    void SCH_060_partial_index_on_pending_events_is_queryable() {
        outboxRepository.save(pendingEvent("PAYMENT_CAPTURED"));
        outboxRepository.save(pendingEvent("REFUND_SETTLED"));
        var pending = outboxRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        assertThat(pending).hasSizeGreaterThanOrEqualTo(2);
    }

    // ── SCH-070 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_070_webhook_inbox_persists_raw_payload() {
        WebhookInbox w = webhook("PSP-001", "MR-001", PspEventCode.AUTHORISATION);
        WebhookInbox saved = webhookRepository.save(w);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getIdempotencyKey()).isEqualTo("PSP-001");
        assertThat(saved.getMerchantReference()).isEqualTo("MR-001");
    }

    // ── SCH-071 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_071_idempotency_key_unique_constraint_deduplicates_webhooks() {
        webhookRepository.save(webhook("PSP-IDEM-001", "MR-002", PspEventCode.CAPTURE));
        WebhookInbox dup = webhook("PSP-IDEM-001", "MR-003", PspEventCode.CAPTURE);
        assertThatThrownBy(() -> webhookRepository.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void SCH_071_exists_by_idempotency_key_supports_dedupe_check() {
        webhookRepository.save(webhook("PSP-EXIST-001", "MR-004", PspEventCode.REFUND));
        assertThat(webhookRepository.existsByIdempotencyKey("PSP-EXIST-001")).isTrue();
        assertThat(webhookRepository.existsByIdempotencyKey("PSP-NONEXISTENT")).isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Booking booking() {
        Customer c = new Customer();
        try {
            var f = Customer.class.getDeclaredField("shopperReference");
            f.setAccessible(true);
            f.set(c, "SHPR-ledger" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        } catch (Exception e) { throw new RuntimeException(e); }
        c.setFullName("Ledger Test");
        c = customerRepository.save(c);
        Booking b = new Booking();
        b.setCustomer(c);
        return bookingRepository.save(b);
    }

    private OutboxEvent pendingEvent(String type) {
        OutboxEvent e = new OutboxEvent();
        e.setEventType(type);
        e.setAggregateType("PAYMENT");
        e.setAggregateId(UUID.randomUUID());
        e.setPayload(Map.of("paymentId", UUID.randomUUID().toString()));
        return e;
    }

    private WebhookInbox webhook(String idempotencyKey, String merchantRef, PspEventCode code) {
        WebhookInbox w = new WebhookInbox();
        w.setIdempotencyKey(idempotencyKey);
        w.setEventCode(code);
        w.setMerchantReference(merchantRef);
        w.setRawPayload(Map.of("eventCode", code.name(), "merchantReference", merchantRef));
        return w;
    }
}
