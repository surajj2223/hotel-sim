package com.hotelops.paymentssim.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hotelops.paymentssim.TestcontainersConfiguration;
import com.hotelops.paymentssim.domain.PspEventCode;
import com.hotelops.paymentssim.domain.PspEventSequenceId;
import com.hotelops.paymentssim.domain.PspEventSequenceRepository;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.DockerClientFactory;

/**
 * PSP-011 / WHK-003 / Trap C — the {@code idempotencyKey} is deterministic:
 * {@code <pspReference>:<eventCode>:<seq>}, and a redelivery REUSES the
 * {@code psp_event_sequence} row (seq is NOT incremented). Two emissions of the same
 * logical event therefore carry identical keys, which is what keeps {@code core-api}'s
 * WHK-005 inbox dedupe correct.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotencyKeyDeterminismTest {

    @BeforeAll
    static void requireDocker() {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            Assumptions.abort("Skipping: no container runtime available.");
        }
    }

    @Autowired
    PspTriggerService triggerService;
    @Autowired
    PspEventSequenceRepository sequences;

    @Test
    void redeliveryReusesSeqAndKeyIsStable() {
        String pspRef = "PSP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        int first = triggerService.findOrCreateSeq(pspRef, PspEventCode.CAPTURE);
        int second = triggerService.findOrCreateSeq(pspRef, PspEventCode.CAPTURE);

        // Redelivery does not bump the sequence — single row, seq stays 1.
        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(1);
        var row = sequences.findById(new PspEventSequenceId(pspRef, "CAPTURE")).orElseThrow();
        assertThat(row.getSeq()).isEqualTo(1);

        String k1 = PspTriggerService.idempotencyKey(pspRef, PspEventCode.CAPTURE, first);
        String k2 = PspTriggerService.idempotencyKey(pspRef, PspEventCode.CAPTURE, second);
        assertThat(k1).isEqualTo(k2).isEqualTo(pspRef + ":CAPTURE:1");
    }

    @Test
    void differentEventCodesGetIndependentSequences() {
        String pspRef = "PSP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        int capture = triggerService.findOrCreateSeq(pspRef, PspEventCode.CAPTURE);
        int refund = triggerService.findOrCreateSeq(pspRef, PspEventCode.REFUND);

        assertThat(capture).isEqualTo(1);
        assertThat(refund).isEqualTo(1);
        assertThat(PspTriggerService.idempotencyKey(pspRef, PspEventCode.CAPTURE, capture))
                .isNotEqualTo(PspTriggerService.idempotencyKey(pspRef, PspEventCode.REFUND, refund));
    }
}
