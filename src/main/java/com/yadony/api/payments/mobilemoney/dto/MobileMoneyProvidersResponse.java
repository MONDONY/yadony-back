package com.yadony.api.payments.mobilemoney.dto;

import java.util.List;

/**
 * Réseaux mobile money utilisables sur un numéro. Le numéro n'apparaît que masqué.
 * {@code detected} : opérateur prédit par pawaPay s'il figure dans la liste, sinon nul.
 */
public record MobileMoneyProvidersResponse(String country, String currency, String msisdnMasked, String detected,
                                           List<ProviderOption> providers) {
    public record ProviderOption(String code, String label, boolean detected) {}
}
