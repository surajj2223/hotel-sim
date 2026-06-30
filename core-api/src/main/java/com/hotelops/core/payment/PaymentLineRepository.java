package com.hotelops.core.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** WHK-016 — scoped payment→line coverage rows. */
public interface PaymentLineRepository extends JpaRepository<PaymentLine, UUID> {

    List<PaymentLine> findByPaymentId(UUID paymentId);

    @Query("""
           SELECT COALESCE(SUM(pl.amount), 0)
           FROM PaymentLine pl
           WHERE pl.payment.id = :paymentId
           """)
    long sumByPaymentId(@Param("paymentId") UUID paymentId);

    /**
     * API-017 listUnpaidBookings — per-line HELD-AUTH total, batched across all unpaid lines
     * (one GROUP BY, no N+1). Rows are {@code [bookingLineId(UUID), sum(amount)(Long)]}.
     *
     * AUTHORISED parent payments ONLY: this figure is informational ("secured" against the line)
     * and must NOT feed lineOwes (a held auth does not reduce what the customer owes — RX-003).
     * Reflects committed DB state, so a capture requested but not yet webhook-confirmed still
     * counts as held here (eventual-consistency caveat, per the contract).
     */
    @Query("""
           SELECT pl.bookingLine.id, SUM(pl.amount)
           FROM PaymentLine pl
           WHERE pl.payment.status = com.hotelops.core.common.enums.PaymentStatus.AUTHORISED
             AND pl.bookingLine.id IN :lineIds
           GROUP BY pl.bookingLine.id
           """)
    List<Object[]> sumHeldAuthByLine(@Param("lineIds") Collection<UUID> lineIds);
}
