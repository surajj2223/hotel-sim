package com.hotelops.paymentssim.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PspRefundRepository extends JpaRepository<PspRefund, UUID> {

    Optional<PspRefund> findByRefundMerchantReference(String refundMerchantReference);

    /**
     * Sum of pending refund amounts against a parent psp_reference. Used together with
     * {@code amount_captured - amount_refunded} to compute remaining capturable when
     * validating PSP-004 (PENDING refunds are queued but not yet settled by 1C).
     */
    @Query("""
           SELECT COALESCE(SUM(r.amount), 0)
           FROM PspRefund r
           WHERE r.originalReference = :originalReference
             AND r.status = com.hotelops.paymentssim.domain.PspRefundStatus.PENDING
           """)
    long sumPendingByOriginalReference(@Param("originalReference") String originalReference);
}
