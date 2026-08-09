package com.yadony.api.payments.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record CurrencyAmount(BigDecimal major, long minor, SupportedCurrency currency) {

    public CurrencyAmount {
        Objects.requireNonNull(major, "major");
        Objects.requireNonNull(currency, "currency");
    }

    public static CurrencyAmount of(BigDecimal major, SupportedCurrency currency) {
        Objects.requireNonNull(major, "major");
        Objects.requireNonNull(currency, "currency");

        BigDecimal rounded = major.setScale(currency.minorUnit(), RoundingMode.HALF_UP);
        long minor = rounded.movePointRight(currency.minorUnit()).longValueExact();
        return new CurrencyAmount(rounded, minor, currency);
    }
}
