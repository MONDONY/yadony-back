package com.yadony.api.payments.currency;

import java.util.Locale;

public enum SupportedCurrency {
    EUR("eur", 2, 1),
    USD("usd", 2, 1),
    CAD("cad", 2, 2),
    GBP("gbp", 2, 1),
    CHF("chf", 2, 1),
    XOF("xof", 0, 655),
    XAF("xaf", 0, 655);

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
    private final int boundScale;

    SupportedCurrency(String code, int minorUnit, int boundScale) {
        this.code = code;
        this.minorUnit = minorUnit;
        this.boundScale = boundScale;
    }

    public String code() {
        return code;
    }

    public int minorUnit() {
        return minorUnit;
    }

    /**
     * Ordre de grandeur de cette devise face à l'euro, servant <b>uniquement</b> à
     * dimensionner les bornes de validation (prix maximum au kilo, montant minimum
     * de rechargement…).
     *
     * <p><b>Ce n'est pas un taux de change et il ne doit jamais servir à convertir
     * un montant.</b> Le partitionnement des devises est strict : un montant reste
     * dans la devise où il a été créé, sans conversion (voir CurrencyMatchGuard).
     * La valeur est volontairement grossière — elle borne un formulaire, elle ne
     * calcule pas un prix.
     *
     * <p>Sans ce facteur, les bornes exprimées en unités euro deviennent absurdes
     * ailleurs : un plafond de 500 vaut 500 €/kg en euro mais 0,76 €/kg en franc
     * CFA, ce qui empêchait purement et simplement un voyageur en XOF de publier
     * un tarif réaliste. Pour XOF et XAF le facteur n'est d'ailleurs pas une
     * estimation : leur parité avec l'euro est fixe (655,957).
     */
    public int boundScale() {
        return boundScale;
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
