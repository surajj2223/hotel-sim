package com.hotelops.core.invariant;

import com.hotelops.core.booking.Booking;
import com.hotelops.core.booking.BookingLine;
import com.hotelops.core.booking.BookingLineRepository;
import com.hotelops.core.booking.BookingRepository;
import com.hotelops.core.booking.BookingService;
import com.hotelops.core.common.auth.HumanAuthorizationGate;
import com.hotelops.core.common.enums.*;
import com.hotelops.core.common.error.HumanAuthRequiredException;
import com.hotelops.core.common.error.StateChangedException;
import com.hotelops.core.customer.Customer;
import com.hotelops.core.customer.CustomerPreferenceRepository;
import com.hotelops.core.customer.CustomerRepository;
import com.hotelops.core.customer.CustomerService;
import com.hotelops.core.ledger.*;
import com.hotelops.core.payment.*;
import com.hotelops.core.product.Product;
import com.hotelops.core.product.ProductRepository;
import com.hotelops.core.product.ProductRoom;
import com.hotelops.core.product.ProductService;
import com.hotelops.core.product.vertical.VerticalStrategy;
import com.hotelops.core.product.vertical.VerticalStrategyRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests proving INV-001 through INV-007.
 * These are fast, no-DB tests that exercise the service-layer invariants directly.
 */
@ExtendWith(MockitoExtension.class)
class InvariantTest {

    // ── INV-001 ──────────────────────────────────────────────────────────────

    @Mock CustomerRepository customerRepository;
    @Mock CustomerPreferenceRepository preferenceRepository;

    @Test
    void INV_001_shopper_reference_format_is_SHPR_prefixed() {
        String ref = CustomerService.mintShopperReference();
        assertThat(ref).matches("^SHPR-[A-Za-z0-9]{32}$");
    }

    @Test
    void INV_001_customer_update_does_not_accept_shopper_reference_change() {
        // CustomerService.updateCustomer has no shopperReference parameter — immutability
        // is enforced by the method signature itself; we verify the method signature here.
        CustomerService svc = new CustomerService(customerRepository, preferenceRepository);
        UUID id = UUID.randomUUID();
        Customer existing = new Customer();
        try {
            var f = Customer.class.getDeclaredField("shopperReference");
            f.setAccessible(true);
            f.set(existing, "SHPR-original00000000000000000000000000");
        } catch (Exception e) { throw new RuntimeException(e); }
        existing.setFullName("Original Name");
        when(customerRepository.findById(id)).thenReturn(Optional.of(existing));
        when(customerRepository.save(any())).thenAnswer(i -> i.getArguments()[0]);

        // updateCustomer has no way to pass a shopperReference — the method doesn't expose it
        Customer updated = svc.updateCustomer(id, "New Name", null, null);
        // shopperReference remains unchanged
        assertThat(updated.getShopperReference()).isEqualTo("SHPR-original00000000000000000000000000");
    }

    // ── INV-002 ──────────────────────────────────────────────────────────────

    @Mock ProductRepository productRepository;

    @Test
    void INV_002_product_service_creates_ROOM_with_correct_child_type() {
        when(productRepository.save(any(ProductRoom.class)))
                .thenAnswer(i -> i.getArguments()[0]);
        ProductService svc = new ProductService(productRepository);
        Product result = svc.createRoom("Deluxe Room", 20000, "GBP", "HIGH", "KING", 2, false, 5);
        assertThat(result).isInstanceOf(ProductRoom.class);
        // Vertical is set by the @DiscriminatorValue — reading it from the mapping
        verify(productRepository).save(argThat(p -> p instanceof ProductRoom));
    }

    // ── INV-003 ──────────────────────────────────────────────────────────────

    @Mock BookingRepository bookingRepository;
    @Mock BookingLineRepository bookingLineRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock RefundRepository refundRepository;
    @Mock CustomerRepository customerRepository2;
    @Mock VerticalStrategyRegistry strategyRegistry;

    @Test
    void INV_003_add_line_throws_409_when_availability_moves() {
        UUID bookingId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Booking booking = new Booking();
        booking.setCustomer(new Customer());

        ProductRoom product = new ProductRoom();
        product.setName("Room");
        product.setBasePrice(10000L);
        product.setCurrency("GBP");

        VerticalStrategy strategy = mock(VerticalStrategy.class);
        when(strategyRegistry.forVertical(any())).thenReturn(strategy);
        when(strategy.availableCapacity(any(), any(), any())).thenReturn(0);   // sold out!

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        BookingService svc = new BookingService(
                bookingRepository, bookingLineRepository, productRepository,
                customerRepository, paymentRepository, refundRepository, strategyRegistry);

        assertThatThrownBy(() -> svc.addLine(bookingId, productId,
                OffsetDateTime.now(), OffsetDateTime.now().plusDays(1), 1))
                .isInstanceOf(StateChangedException.class)
                .hasMessageContaining("Insufficient availability");
    }

    // ── INV-004 ──────────────────────────────────────────────────────────────

    @Test
    void INV_004_recalculate_totals_sets_correct_amounts() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = new Booking();
        try {
            var f = Booking.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(booking, bookingId);
        } catch (Exception e) { throw new RuntimeException(e); }

        when(bookingRepository.sumActiveLineAmounts(bookingId)).thenReturn(50000L);
        when(paymentRepository.sumCapturedForBooking(bookingId)).thenReturn(30000L);
        when(refundRepository.sumSettledRefundsForBooking(bookingId)).thenReturn(5000L);

        BookingService svc = new BookingService(
                bookingRepository, bookingLineRepository, productRepository,
                customerRepository, paymentRepository, refundRepository, strategyRegistry);

        svc.recalculateTotals(booking);

        assertThat(booking.getTotalAmount()).isEqualTo(50000L);
        assertThat(booking.getAmountPaid()).isEqualTo(30000L);
        assertThat(booking.getAmountRefunded()).isEqualTo(5000L);
        // balance = 50000 - 30000 + 5000 = 25000
        assertThat(booking.getBalance()).isEqualTo(25000L);
    }

    // ── INV-005 ──────────────────────────────────────────────────────────────

    @Mock OutboxEventRepository outboxRepository;
    @Mock LedgerService ledgerService;
    @Mock BookingService bookingService;

    /**
     * Helper: build a PaymentService with the test mocks. Mirrors the production
     * constructor; strategyRegistry is the class-level {@link #strategyRegistry} mock.
     */
    private PaymentService newPaymentService() {
        return new PaymentService(
                paymentRepository, refundRepository, bookingRepository,
                bookingLineRepository, bookingService, outboxRepository,
                strategyRegistry);
    }

    @Test
    void INV_005_second_capture_attempt_is_rejected() {
        // WHK-015: validation lives on the request side; settle must also re-check
        // defensively. We exercise the request side here.
        UUID paymentId = UUID.randomUUID();
        Payment p = new Payment();
        try {
            var f = Payment.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, paymentId);
        } catch (Exception e) { throw new RuntimeException(e); }
        p.setStatus(PaymentStatus.CAPTURED);   // already captured
        p.setAmountAuthorised(10000L);
        p.setAmountCaptured(10000L);

        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        PaymentService svc = newPaymentService();

        assertThatThrownBy(() -> svc.requestCapture(paymentId, 5000L))
                .isInstanceOf(StateChangedException.class)
                .hasMessageContaining("INV-005");
    }

    // ── INV-006 ──────────────────────────────────────────────────────────────

    @Test
    void INV_006_authorisation_produces_no_outbox_event() {
        PaymentService svc = newPaymentService();

        Payment p = new Payment();
        p.setMerchantReference("MR-NO-POST");
        p.setAmountAuthorised(0L);
        p.setAmountCaptured(0L);
        p.setAmountRefunded(0L);
        p.setAmountRequested(10000L);
        p.setStatus(PaymentStatus.PENDING);

        when(paymentRepository.findByMerchantReference("MR-NO-POST")).thenReturn(Optional.of(p));
        when(paymentRepository.save(any())).thenAnswer(i -> i.getArguments()[0]);

        svc.recordAuthorisation("MR-NO-POST", "PSP-001", 10000L, null);

        // INV-006: no outbox event should be saved for authorisation
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void INV_006_capture_settle_enqueues_PAYMENT_CAPTURED_outbox_event() {
        // WHK-007/015: outbox emission happens on the settle side (CAPTURE webhook),
        // not on the operator-facing request side.
        UUID paymentId = UUID.randomUUID();
        Payment p = new Payment();
        try {
            var f = Payment.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, paymentId);
        } catch (Exception e) { throw new RuntimeException(e); }
        p.setStatus(PaymentStatus.AUTHORISED);
        p.setAmountAuthorised(10000L);
        p.setAmountCaptured(0L);
        p.setAmountRefunded(0L);
        p.setCaptureMode(CaptureMode.MANUAL);
        p.setMerchantReference("MR-CAPTURE-001");

        Booking booking = new Booking();
        try {
            var bf = Booking.class.getDeclaredField("id");
            bf.setAccessible(true);
            bf.set(booking, UUID.randomUUID());
        } catch (Exception e) { throw new RuntimeException(e); }
        p.setBooking(booking);

        when(paymentRepository.findByMerchantReference("MR-CAPTURE-001"))
                .thenReturn(Optional.of(p));
        when(paymentRepository.save(any())).thenAnswer(i -> i.getArguments()[0]);
        when(outboxRepository.save(any())).thenAnswer(i -> i.getArguments()[0]);
        doNothing().when(bookingService).recalculateTotals(any());
        when(bookingRepository.save(any())).thenAnswer(i -> i.getArguments()[0]);

        PaymentService svc = newPaymentService();

        svc.settleCapture("MR-CAPTURE-001", 10000L, "PSP-CAPTURE-001");

        // INV-006: exactly one outbox event of type PAYMENT_CAPTURED
        verify(outboxRepository).save(argThat(e ->
                "PAYMENT_CAPTURED".equals(e.getEventType())
                && "PAYMENT".equals(e.getAggregateType())));
    }

    @Test
    void INV_006_cancellation_settle_produces_no_outbox_event() {
        // WHK-008/015: CANCELLATION webhook flips state to CANCELLED with no outbox event
        // and no ledger posting — nothing was captured.
        Payment p = new Payment();
        try {
            var f = Payment.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, UUID.randomUUID());
        } catch (Exception e) { throw new RuntimeException(e); }
        p.setStatus(PaymentStatus.AUTHORISED);
        p.setAmountCaptured(0L);
        p.setAmountRefunded(0L);
        p.setMerchantReference("MR-CANCEL-001");

        when(paymentRepository.findByMerchantReference("MR-CANCEL-001"))
                .thenReturn(Optional.of(p));
        when(paymentRepository.save(any())).thenAnswer(i -> i.getArguments()[0]);

        PaymentService svc = newPaymentService();

        svc.settleCancellation("MR-CANCEL-001");

        // INV-006: no outbox event for cancellation of uncaptured auth
        verify(outboxRepository, never()).save(any());
    }

    // ── INV-007 ──────────────────────────────────────────────────────────────

    @Test
    void INV_007_human_auth_gate_rejects_null_token() {
        HumanAuthorizationGate gate = new HumanAuthorizationGate();
        assertThatThrownBy(() -> gate.assertAuthorised(null, "createBooking"))
                .isInstanceOf(HumanAuthRequiredException.class)
                .hasMessageContaining("createBooking");
    }

    @Test
    void INV_007_human_auth_gate_rejects_blank_token() {
        HumanAuthorizationGate gate = new HumanAuthorizationGate();
        assertThatThrownBy(() -> gate.assertAuthorised("   ", "capturePayment"))
                .isInstanceOf(HumanAuthRequiredException.class);
    }

    @Test
    void INV_007_human_auth_gate_passes_with_present_token() {
        HumanAuthorizationGate gate = new HumanAuthorizationGate();
        assertThatCode(() -> gate.assertAuthorised("human-confirmed-yes", "createBooking"))
                .doesNotThrowAnyException();
    }

    @Test
    void INV_007_exception_maps_to_http_428_precondition_required() {
        var annotation = HumanAuthRequiredException.class.getAnnotation(
                org.springframework.web.bind.annotation.ResponseStatus.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo(org.springframework.http.HttpStatus.PRECONDITION_REQUIRED);
    }
}
