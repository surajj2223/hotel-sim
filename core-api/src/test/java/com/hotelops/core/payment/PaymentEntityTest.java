package com.hotelops.core.payment;

import com.hotelops.core.AbstractDataJpaTest;
import com.hotelops.core.booking.Booking;
import com.hotelops.core.booking.BookingRepository;
import com.hotelops.core.common.enums.CaptureMode;
import com.hotelops.core.common.enums.PaymentStatus;
import com.hotelops.core.common.enums.RefundStatus;
import com.hotelops.core.customer.Customer;
import com.hotelops.core.customer.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests proving SCH-030, SCH-031, SCH-032, SCH-033, SCH-040.
 * SCH-034 (single-capture rule INV-005) is tested in InvariantTest (service layer).
 */
class PaymentEntityTest extends AbstractDataJpaTest {

    @Autowired PaymentRepository paymentRepository;
    @Autowired RefundRepository refundRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired CustomerRepository customerRepository;

    // ── SCH-030 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_030_payment_persists_with_reference_taxonomy() {
        Payment p = payment("MR-001");
        Payment saved = paymentRepository.save(p);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getMerchantReference()).isEqualTo("MR-001");
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getCaptureMode()).isEqualTo(CaptureMode.IMMEDIATE);
    }

    // ── SCH-031 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_031_merchant_reference_must_be_unique() {
        paymentRepository.save(payment("MR-DUP-001"));
        Payment dup = payment("MR-DUP-001");
        assertThatThrownBy(() -> paymentRepository.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void SCH_031_merchant_reference_indexed_and_queryable() {
        paymentRepository.save(payment("MR-FIND-001"));
        assertThat(paymentRepository.findByMerchantReference("MR-FIND-001")).isPresent();
    }

    // ── SCH-032 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_032_amount_captured_must_not_exceed_authorised() {
        Payment p = paymentRepository.save(payment("MR-CHK-001"));
        p.setAmountAuthorised(10000L);
        p.setAmountCaptured(10001L);   // violates CHECK
        assertThatThrownBy(() -> paymentRepository.saveAndFlush(p))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void SCH_032_partial_capture_within_authorised_is_valid() {
        Payment p = paymentRepository.save(payment("MR-PARTIAL-001"));
        p.setAmountAuthorised(10000L);
        p.setAmountCaptured(7500L);   // partial capture — valid
        assertThatCode(() -> paymentRepository.saveAndFlush(p)).doesNotThrowAnyException();
    }

    // ── SCH-033 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_033_amount_refunded_must_not_exceed_captured() {
        Payment p = paymentRepository.save(payment("MR-REFCHK-001"));
        p.setAmountAuthorised(10000L);
        p.setAmountCaptured(8000L);
        p.setAmountRefunded(8001L);   // violates CHECK
        assertThatThrownBy(() -> paymentRepository.saveAndFlush(p))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void SCH_033_full_refund_of_captured_is_valid() {
        Payment p = paymentRepository.save(payment("MR-FULLREF-001"));
        p.setAmountAuthorised(10000L);
        p.setAmountCaptured(8000L);
        p.setAmountRefunded(8000L);   // full refund — valid
        assertThatCode(() -> paymentRepository.saveAndFlush(p)).doesNotThrowAnyException();
    }

    // ── SCH-040 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_040_refund_persists_with_original_reference_chain() {
        Payment p = paymentRepository.save(payment("MR-REFUND-P01"));
        p.setAmountAuthorised(20000L);
        p.setAmountCaptured(20000L);
        p.setPspReference("PSP-PARENT-001");
        paymentRepository.save(p);

        Refund r = new Refund();
        r.setPayment(p);
        r.setAmount(5000L);
        r.setMerchantReference("MR-REFUND-R01");
        r.setOriginalReference("PSP-PARENT-001");   // parent/child chain

        Refund saved = refundRepository.save(r);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getOriginalReference()).isEqualTo("PSP-PARENT-001");
        assertThat(saved.getStatus()).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void SCH_040_refund_amount_must_be_positive() {
        Payment p = paymentRepository.save(payment("MR-REFNEG-P01"));
        Refund r = new Refund();
        r.setPayment(p);
        r.setAmount(0L);   // must be > 0
        r.setMerchantReference("MR-REFNEG-R01");
        r.setOriginalReference("PSP-000");
        assertThatThrownBy(() -> refundRepository.saveAndFlush(r))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void SCH_040_refund_merchant_reference_must_be_unique() {
        Payment p = paymentRepository.save(payment("MR-REFDUP-P01"));
        saveRefund(p, "MR-REFDUP-R01");
        assertThatThrownBy(() -> saveRefund(p, "MR-REFDUP-R01"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Payment payment(String merchantRef) {
        Booking b = booking();
        Payment p = new Payment();
        p.setBooking(b);
        p.setShopperReference("SHPR-payment0001x");
        p.setMerchantReference(merchantRef);
        p.setCaptureMode(CaptureMode.IMMEDIATE);
        p.setAmountRequested(10000L);
        return p;
    }

    private Booking booking() {
        Customer c = new Customer();
        try {
            var f = Customer.class.getDeclaredField("shopperReference");
            f.setAccessible(true);
            f.set(c, "SHPR-pay" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        } catch (Exception e) { throw new RuntimeException(e); }
        c.setFullName("Pay Test");
        c = customerRepository.save(c);
        Booking b = new Booking();
        b.setCustomer(c);
        return bookingRepository.save(b);
    }

    private Refund saveRefund(Payment p, String merchantRef) {
        Refund r = new Refund();
        r.setPayment(p);
        r.setAmount(1000L);
        r.setMerchantReference(merchantRef);
        r.setOriginalReference("PSP-ORIG");
        return refundRepository.saveAndFlush(r);
    }
}
