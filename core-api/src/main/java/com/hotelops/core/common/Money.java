package com.hotelops.core.common;

/**
 * Immutable monetary value: minor-units amount (e.g. pence) + ISO-4217 currency code.
 * Money is ALWAYS integer minor units — never float/double (see project brief §6).
 * ENM-010 reference: currency codes used throughout the system.
 */
public record Money(long amount, String currency) {

    public static Money gbp(long amountPence) {
        return new Money(amountPence, "GBP");
    }

    public static Money of(long amount, String currency) {
        return new Money(amount, currency);
    }

    public Money add(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount + other.amount, this.currency);
    }

    public Money subtract(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount - other.amount, this.currency);
    }

    private void assertSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Cannot operate on different currencies: " + this.currency + " vs " + other.currency);
        }
    }
}
