package com.hotelops.paymentssim.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PspEventSequenceRepository
        extends JpaRepository<PspEventSequence, PspEventSequenceId> {
}
