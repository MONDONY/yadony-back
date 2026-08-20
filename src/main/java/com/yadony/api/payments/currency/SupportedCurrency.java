package com.yadony.api.payments.currency;

import java.util.Locale;

public enum SupportedCurrency {
    EUR("eur", 2, "1", "\u20AC"),
    USD("usd", 2, "1.08", "$"),
    CAD("cad", 2, "1.47", "CA$"),
    GBP("gbp", 2, "0.86", "\u00A3"),
    CHF("chf", 2, "0.95", "CHF"),
    XOF("xof", 0, "655.957", "F CFA"),
    XAF("xaf", 0, "655.957", "FCFA");

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
    private final java.math.BigDecimal unitsPerEur;
    private final String symbol;

    SupportedCurrency(String code, int minorUnit, String unitsPerEur, String symbol) {
        this.symbol = symbol;
        this.code = code;
        this.minorUnit = minorUnit;
        this.unitsPerEur = new java.math.BigDecimal(unitsPerEur);
    }

    public String code() {
        return code;
    }

    /**
     * Symbole court, pour les libelles ou formater un montant complet n'a pas de sens
     * (affiche partageable, page publique). Aligne sur le catalogue Dart
     * {@code lib/core/currency/supported_currency.dart} : les deux surfaces montrent le
     * meme trajet et ne doivent pas diverger.
     */
    public String symbol() {
        return symbol;
    }

    /** Symbole d'un code libre, avec le repli EUR unique de {@link #fromCodeOrDefault}. */
    public static String symbolOf(String code) {
        return fromCodeOrDefault(code).symbol();
    }

    public int minorUnit() {
        return minorUnit;
    }

    /**
     * Nombre d'unités de cette devise pour un euro.
     *
     * <p>Sert à exprimer dans la devise de l'utilisateur les <b>barèmes définis en
     * euros</b> : bornes de validation (prix maximum au kilo, montant minimum de
     * rechargement) et montants promotionnels comme la récompense de parrainage.
     * Une valeur figée à 500 vaut 500 €/kg en euro mais 0,76 €/kg en franc CFA,
     * et une récompense de 5 y devient 0,008 € : sans mise à l'échelle, ces règles
     * n'ont aucun sens hors zone euro.
     *
     * <p><b>Ne jamais s'en servir pour convertir le montant d'une transaction entre
     * deux parties.</b> Le partitionnement des devises reste strict au niveau du
     * prix : un bid reprend la devise de l'annonce, une négociation celle de la
     * demande de colis, jamais celle de la contrepartie qui répond — l'appariement
     * entre devises différentes est autorisé (aucune devise commune n'est exigée),
     * mais le montant, lui, ne change jamais de devise. Ce facteur traduit un
     * barème interne, il n'arbitre aucun échange.
     *
     * <p>Pour XOF et XAF ce n'est pas une estimation de marché : leur parité avec
     * l'euro est fixe (655,957). Les autres valeurs sont des ordres de grandeur qui
     * dérivent avec le marché ; elles dimensionnent des bornes et des cadeaux, pas
     * des prix, et une imprécision de quelques pour cent y est sans conséquence.
     */
    public java.math.BigDecimal unitsPerEur() {
        return unitsPerEur;
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
