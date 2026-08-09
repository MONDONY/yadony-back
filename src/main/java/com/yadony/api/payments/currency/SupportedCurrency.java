package com.yadony.api.payments.currency;

import java.util.Locale;

public enum SupportedCurrency {
    EUR("eur", 2),
    USD("usd", 2),
    CAD("cad", 2),
    GBP("gbp", 2),
    CHF("chf", 2),
    XOF("xof", 0),
    XAF("xaf", 0);

    private final String code;
    private final int minorUnit;

    SupportedCurrency(String code, int minorUnit) {
        this.code = code;
        this.minorUnit = minorUnit;
    }

    public String code() {
        return code;
    }

    public int minorUnit() {
        return minorUnit;
    }

    public static SupportedCurrency fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (SupportedCurrency currency : values()) {
            if (currency.code.equals(normalized)) {
                return currency;
            }
        }
        return null;
    }

    public static SupportedCurrency defaultForCountry(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return EUR;
        }
        return switch (countryCode.trim().toUpperCase(Locale.ROOT)) {
            case "US" -> USD;
            case "CA" -> CAD;
            case "GB" -> GBP;
            case "CH" -> CHF;
            case "SN", "CI", "ML", "BF", "BJ", "TG", "NE", "GW" -> XOF;
            case "CM", "GA", "CG", "TD", "CF", "GQ" -> XAF;
            default -> EUR;
        };
    }
}
