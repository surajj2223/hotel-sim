package com.hotelops.paymentssim.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PspPaymentRepository extends JpaRepository<PspPayment, UUID> {

    Optional<PspPayment> findByMerchantReference(String merchantReference);

    Optional<PspPayment> findByPspReference(String pspReference);

    Optional<PspPayment> findByPaymentLinkId(String paymentLinkId);

    /**
     * Row-lock the parent payment while a refund is being validated + inserted, so
     * concurrent refund requests cannot both pass the
     * {@code amount <= captured - refunded - sum(pending)} check (PSP-004 §6.4 flag).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PspPayment p WHERE p.pspReference = :pspReference")
    Optional<PspPayment> lockByPspReference(@Param("pspReference") String pspReference);
}
