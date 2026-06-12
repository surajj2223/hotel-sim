package com.hotelops.paymentssim.common.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hotelops.paymentssim.common.error.PspApiKeyMissingException;
import org.junit.jupiter.api.Test;

class PspApiKeyGateTest {

    private final PspApiKeyGate gate = new PspApiKeyGate();

    @Test
    void presentKeyPasses() {
        assertThatCode(() -> gate.assertPresent("any-non-blank-value")).doesNotThrowAnyException();
    }

    @Test
    void nullKeyThrows() {
        assertThatThrownBy(() -> gate.assertPresent(null))
                .isInstanceOf(PspApiKeyMissingException.class);
    }

    @Test
    void blankKeyThrows() {
        assertThatThrownBy(() -> gate.assertPresent("   "))
                .isInstanceOf(PspApiKeyMissingException.class);
    }
}
