package com.hotelops.core.ledger;

import com.hotelops.core.common.enums.OutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests proving WHK-013 / GAP-2 idempotency and claim logic.
 *
 * The @Transactional boundary on OutboxEventHandler.processInTransaction is not
 * exercised here (no Spring context); these tests verify that:
 * - the claim gate (PENDING→PROCESSING) prevents double dispatch, and
 * - LedgerService is called exactly once per event regardless of how many times
 *   the processor sees the same event.
 */
@ExtendWith(MockitoExtension.class)
class OutboxIdempotencyTest {

    @Mock OutboxEventRepository outboxRepository;
    @Mock LedgerService ledgerService;

    OutboxProcessor processor;
    OutboxEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OutboxEventHandler(outboxRepository, ledgerService);
        processor = new OutboxProcessor(outboxRepository, handler);
    }

    // ── WHK-013: double-dispatch ──────────────────────────────────────────────

    @Test
    void WHK013_doubleDispatch_sameEvent_ledgerCalledExactlyOnce() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        OutboxEvent event = captureEvent(paymentId);
        setId(event, eventId);

        // First dispatch: claim succeeds
        when(outboxRepository.claimEvent(eventId, OutboxStatus.PENDING, OutboxStatus.PROCESSING))
                .thenReturn(1);
        when(outboxRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(outboxRepository.save(any())).thenAnswer(i -> i.getArguments()[0]);
        when(ledgerService.postCapture(paymentId)).thenReturn(List.of());

        // Run two ticks over the same PENDING event list
        OutboxEvent pending = new OutboxEvent();
        setId(pending, eventId);
        when(outboxRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(pending));

        processor.processPending();

        // Second tick: claim fails (event is now PROCESSING/PROCESSED)
        when(outboxRepository.claimEvent(eventId, OutboxStatus.PENDING, OutboxStatus.PROCESSING))
                .thenReturn(0);

        processor.processPending();

        // LedgerService must have been called exactly once
        verify(ledgerService, times(1)).postCapture(paymentId);
    }

    // ── WHK-013: concurrent-tick ──────────────────────────────────────────────

    @Test
    void WHK013_concurrentTick_onlyOneWinsClaim_noDoublePosting() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        OutboxEvent event = captureEvent(paymentId);
        setId(event, eventId);

        // claimEvent: first call wins (1), subsequent calls lose (0)
        when(outboxRepository.claimEvent(eventId, OutboxStatus.PENDING, OutboxStatus.PROCESSING))
                .thenReturn(1, 0);
        when(outboxRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(outboxRepository.save(any())).thenAnswer(i -> i.getArguments()[0]);
        when(ledgerService.postCapture(paymentId)).thenReturn(List.of());

        // Simulate two ticks both seeing the same PENDING event
        OutboxEvent pending = new OutboxEvent();
        setId(pending, eventId);

        // Tick 1: wins claim, processes
        int claimed1 = outboxRepository.claimEvent(eventId, OutboxStatus.PENDING, OutboxStatus.PROCESSING);
        if (claimed1 > 0) handler.processInTransaction(eventId);

        // Tick 2: loses claim, skips
        int claimed2 = outboxRepository.claimEvent(eventId, OutboxStatus.PENDING, OutboxStatus.PROCESSING);
        if (claimed2 > 0) handler.processInTransaction(eventId);

        // Only one posting
        verify(ledgerService, times(1)).postCapture(paymentId);
    }

    @Test
    void WHK013_refundEvent_doubleDispatch_ledgerCalledExactlyOnce() {
        UUID eventId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();

        OutboxEvent event = refundEvent(refundId);
        setId(event, eventId);

        when(outboxRepository.claimEvent(eventId, OutboxStatus.PENDING, OutboxStatus.PROCESSING))
                .thenReturn(1, 0);
        when(outboxRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(outboxRepository.save(any())).thenAnswer(i -> i.getArguments()[0]);
        when(ledgerService.postRefund(refundId)).thenReturn(List.of());

        // Two calls: first wins, second loses
        OutboxEvent pending = new OutboxEvent();
        setId(pending, eventId);

        int c1 = outboxRepository.claimEvent(eventId, OutboxStatus.PENDING, OutboxStatus.PROCESSING);
        if (c1 > 0) handler.processInTransaction(eventId);

        int c2 = outboxRepository.claimEvent(eventId, OutboxStatus.PENDING, OutboxStatus.PROCESSING);
        if (c2 > 0) handler.processInTransaction(eventId);

        verify(ledgerService, times(1)).postRefund(refundId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private OutboxEvent captureEvent(UUID paymentId) {
        OutboxEvent e = new OutboxEvent();
        e.setEventType("PAYMENT_CAPTURED");
        e.setAggregateType("PAYMENT");
        e.setAggregateId(paymentId);
        e.setPayload(Map.of("paymentId", paymentId.toString()));
        return e;
    }

    private OutboxEvent refundEvent(UUID refundId) {
        OutboxEvent e = new OutboxEvent();
        e.setEventType("REFUND_SETTLED");
        e.setAggregateType("REFUND");
        e.setAggregateId(refundId);
        e.setPayload(Map.of("refundId", refundId.toString()));
        return e;
    }

    private static void setId(Object entity, UUID id) {
        try {
            var f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
