package com.hotelops.core.ledger;

import com.hotelops.core.TestcontainersConfiguration;
import com.hotelops.core.booking.Booking;
import com.hotelops.core.booking.BookingLine;
import com.hotelops.core.booking.BookingLineRepository;
import com.hotelops.core.booking.BookingRepository;
import com.hotelops.core.common.enums.BookingLineStatus;
import com.hotelops.core.common.enums.CaptureMode;
import com.hotelops.core.common.enums.OutboxStatus;
import com.hotelops.core.common.enums.PaymentStatus;
import com.hotelops.core.common.enums.PostingType;
import com.hotelops.core.common.enums.Vertical;
import com.hotelops.core.customer.Customer;
import com.hotelops.core.customer.CustomerRepository;
import com.hotelops.core.payment.Payment;
import com.hotelops.core.payment.PaymentRepository;
import com.hotelops.core.product.ProductRepository;
import com.hotelops.core.product.ProductRoom;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.DockerClientFactory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCH-061 — Flag-2 crash recovery: rows stranded in PROCESSING are reclaimed.
 *
 * Full Spring context against Testcontainers Postgres, so the reclaim runs through the REAL
 * proxied {@link OutboxEventHandler} (true @Transactional boundary) and the option-(b)
 * idempotency backstop (a duplicate INSERT rejected by the V2 unique index surfaces as a
 * {@link org.springframework.dao.DataIntegrityViolationException} at commit) is exercised
 * end-to-end against real SQL.
 *
 * Assertions are on end state (posting rows + outbox status), which is robust to the
 * background {@code @Scheduled} tick possibly running the same reclaim concurrently — the
 * re-claim gate + idempotent handler make the outcome identical either way.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxReclaimTest {

    @BeforeAll
    static void requireDocker() {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            Assumptions.abort("Skipping outbox-reclaim test: no container runtime available.");
        }
    }

    @Autowired OutboxProcessor outboxProcessor;
    @Autowired OutboxEventRepository outboxRepository;
    @Autowired LedgerPostingRepository postingRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired BookingLineRepository lineRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired ProductRepository productRepository;
    @Autowired PaymentRepository paymentRepository;

    private static final long ROOM_RATE = 50000L; // £500 in minor units

    // ── Test 1: stranded PROCESSING row is reclaimed and posts ────────────────────

    @Test
    void SCH_061_strandedProcessingRow_isReclaimed_andLedgerPosts() {
        Fixture f = seedCaptureScenario();
        UUID eventId = seedOutboxEvent(f.paymentId, OutboxStatus.PROCESSING,
                OffsetDateTime.now().minusMinutes(10)); // older than the 2-minute cutoff

        outboxProcessor.processPending();

        // The ledger posting now exists (one REVENUE line for the room line)...
        List<LedgerPosting> postings = postingRepository.findByBookingId(f.bookingId);
        assertThat(postings).hasSize(1);
        assertThat(postings.get(0).getPostingType()).isEqualTo(PostingType.REVENUE);
        assertThat(postings.get(0).getAmount()).isEqualTo(ROOM_RATE);
        // ...and the row advanced to PROCESSED.
        assertThat(outboxRepository.findById(eventId).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PROCESSED);
    }

    // ── Test 2: reclaim of an already-posted row does not double-post ─────────────

    @Test
    void SCH_061_reclaimOfAlreadyPostedRow_noDoublePost_endsProcessed() {
        Fixture f = seedCaptureScenario();
        // Simulate a crash AFTER the posting committed but before the status advanced:
        // a REVENUE posting already exists for (payment, line).
        seedExistingRevenuePosting(f);
        UUID eventId = seedOutboxEvent(f.paymentId, OutboxStatus.PROCESSING,
                OffsetDateTime.now().minusMinutes(10));

        outboxProcessor.processPending();

        // Exactly one posting — the V2 unique index (uq_posting_capture_line) rejects the
        // replay's duplicate INSERT; option (b) treats that as success, not FAILED.
        List<LedgerPosting> postings = postingRepository.findByBookingId(f.bookingId);
        assertThat(postings).hasSize(1);
        assertThat(outboxRepository.findById(eventId).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PROCESSED);
    }

    // ── Test 3: a freshly-claimed PROCESSING row is NOT reclaimed ─────────────────

    @Test
    void SCH_061_freshProcessingRow_withinCutoff_isNotReclaimed() {
        // No valid payment needed — a fresh row must never be dispatched.
        UUID eventId = seedOutboxEvent(UUID.randomUUID(), OutboxStatus.PROCESSING,
                OffsetDateTime.now()); // claimed now → inside the 2-minute cutoff

        outboxProcessor.processPending();

        // Untouched: a merely-slow in-flight handler must not be reclaimed mid-flight (Trap B).
        assertThat(outboxRepository.findById(eventId).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PROCESSING);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────

    private record Fixture(UUID bookingId, UUID bookingLineId, UUID paymentId) {}

    /** A booking with one ACTIVE £500 room line and an IMMEDIATE payment captured in full. */
    private Fixture seedCaptureScenario() {
        Customer c = new Customer();
        setShopperReference(c, "SHPR-reclaim" + shortId());
        c.setFullName("Reclaim Test");
        c = customerRepository.save(c);

        Booking b = new Booking();
        b.setCustomer(c);
        b.setTotalAmount(ROOM_RATE);
        b = bookingRepository.save(b);

        ProductRoom room = new ProductRoom();
        room.setName("Standard Room");
        room.setBasePrice(ROOM_RATE);
        room.setCurrency("GBP");
        room.setRoomCount(10);
        room = productRepository.save(room);

        BookingLine line = new BookingLine();
        line.setBooking(b);
        line.setProduct(room);
        line.setVertical(Vertical.ROOM);
        line.setStatus(BookingLineStatus.ACTIVE);
        line.setStartsAt(OffsetDateTime.now());
        line.setEndsAt(OffsetDateTime.now().plusDays(1));
        line.setQuantity(1);
        line.setUnitPrice(ROOM_RATE);
        line.setLineAmount(ROOM_RATE);
        line = lineRepository.save(line);

        Payment p = new Payment();
        p.setBooking(b);
        p.setShopperReference(c.getShopperReference());
        p.setMerchantReference("MR-reclaim-" + shortId());
        p.setPspReference("PSP-reclaim-" + shortId());
        p.setCaptureMode(CaptureMode.IMMEDIATE);
        p.setStatus(PaymentStatus.CAPTURED);
        p.setCurrency("GBP");
        p.setAmountRequested(ROOM_RATE);
        p.setAmountAuthorised(ROOM_RATE);
        p.setAmountCaptured(ROOM_RATE);
        p = paymentRepository.save(p);

        return new Fixture(b.getId(), line.getId(), p.getId());
    }

    private void seedExistingRevenuePosting(Fixture f) {
        LedgerPosting p = new LedgerPosting();
        p.setPostingType(PostingType.REVENUE);
        p.setBooking(bookingRepository.findById(f.bookingId).orElseThrow());
        p.setBookingLine(lineRepository.findById(f.bookingLineId).orElseThrow());
        p.setPayment(paymentRepository.findById(f.paymentId).orElseThrow());
        p.setVertical(Vertical.ROOM);
        p.setAmount(ROOM_RATE);
        p.setCurrency("GBP");
        postingRepository.save(p);
    }

    private UUID seedOutboxEvent(UUID paymentId, OutboxStatus status, OffsetDateTime claimedAt) {
        OutboxEvent e = new OutboxEvent();
        e.setEventType("PAYMENT_CAPTURED");
        e.setAggregateType("PAYMENT");
        e.setAggregateId(paymentId);
        e.setPayload(Map.of("paymentId", paymentId.toString()));
        e.setStatus(status);
        e.setClaimedAt(claimedAt);
        return outboxRepository.save(e).getId();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static void setShopperReference(Customer c, String ref) {
        try {
            var fld = Customer.class.getDeclaredField("shopperReference");
            fld.setAccessible(true);
            fld.set(c, ref);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
