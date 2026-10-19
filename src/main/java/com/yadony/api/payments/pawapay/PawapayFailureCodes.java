package com.yadony.api.payments.pawapay;

import java.util.Map;

/**
 * Code d'échec pawaPay -> code Yadony stable, exposé aux clients ({@code failureCode} d'une
 * recharge) et clé du message traduit {@code topup.failure.<code>}. Tout code inconnu, vide ou
 * absent tombe sur {@code other} : on ne reflète jamais un code brut de l'opérateur.
 */
public final class PawapayFailureCodes {

    public static final String OTHER = "other";

    private static final Map<String, String> BY_PAWAPAY_CODE = Map.of(
            "INSUFFICIENT_BALANCE", "insufficient-balance",
            "PAYMENT_NOT_APPROVED", "not-approved",
            "PAYER_LIMIT_REACHED", "payer-limit-reached",
            "INVALID_PHONE_NUMBER", "invalid-number",
            "PAYER_NOT_FOUND", "invalid-number",
            "RECIPIENT_NOT_FOUND", "invalid-number",
            "PROVIDER_TEMPORARILY_UNAVAILABLE", "provider-unavailable",
            "SUBMIT_TIMEOUT", "expired");

    private PawapayFailureCodes() {
    }

    /** Code Yadony du code d'échec pawaPay ({@code other} si inconnu ou {@code null}). */
    public static String toYadony(String pawapayCode) {
        return pawapayCode == null ? OTHER : BY_PAWAPAY_CODE.getOrDefault(pawapayCode, OTHER);
    }
}
