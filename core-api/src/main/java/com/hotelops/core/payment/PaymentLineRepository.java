package com.hotelops.core.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
