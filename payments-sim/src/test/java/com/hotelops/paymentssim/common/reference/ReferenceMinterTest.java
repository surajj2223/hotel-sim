package com.hotelops.paymentssim.common.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ReferenceMinterTest {

    private static final Pattern PAYMENT_LINK = Pattern.compile("^PL-[0-9A-Za-z]{16}$");
    private static final Pattern PSP_REFERENCE = Pattern.compile("^PSP-[0-9A-Za-z]{16}$");

    private final ReferenceMinter minter = new ReferenceMinter();

    @Test
    void paymentLinkIdMatchesGrammar() {
        for (int i = 0; i < 100; i++) {
            String id = minter.mintPaymentLinkId();
            assertThat(id).matches(PAYMENT_LINK);
        }
    }

    @Test
    void pspReferenceMatchesGrammar() {
        for (int i = 0; i < 100; i++) {
            String id = minter.mintPspReference();
            assertThat(id).matches(PSP_REFERENCE);
        }
    }

    @Test
    void mintedIdsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            assertThat(seen.add(minter.mintPaymentLinkId())).isTrue();
            assertThat(seen.add(minter.mintPspReference())).isTrue();
        }
    }
}
