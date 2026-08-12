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

    private static final java.util.Map<String, SupportedCurrency> BY_CODE;

    static {
        java.util.Map<String, SupportedCurrency> byCode = new java.util.HashMap<>();
        for (SupportedCurrency currency : values()) {
            byCode.put(currency.code, currency);
        }
        BY_CODE = java.util.Map.copyOf(byCode);
    }

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
        return BY_CODE.get(code.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Single fallback policy for an unknown or absent code. Call sites must use this
     * rather than rewriting {@code fromCode(x)} + a local EUR default.
     */
    public static SupportedCurrency fromCodeOrDefault(String code) {
        SupportedCurrency currency = fromCode(code);
        return currency != null ? currency : EUR;
    }
}
