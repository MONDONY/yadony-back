package com.yadony.api.payments.mobilemoney.dto;

import com.yadony.api.common.Msisdn;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.PawapayProviders;
import java.util.List;

/**
 * Réseaux mobile money utilisables sur un numéro. Le numéro n'apparaît que masqué.
 * {@code detected} : opérateur prédit par pawaPay s'il figure dans la liste, sinon nul.
 */
public record MobileMoneyProvidersResponse(String country, String currency, String msisdnMasked, String detected,
                                           List<ProviderOption> providers) {
    public record ProviderOption(String code, String label, boolean detected) {}

    /**
     * Mise en forme d'un catalogue du résolveur. Ici et pas dans un service : le versement
     * (compte mobile money) et la recharge du portefeuille rendent la MÊME liste au client,
     * une divergence de forme entre les deux écrans serait un bug invisible côté serveur.
     */
    public static MobileMoneyProvidersResponse from(PawapayProviderResolver.Catalogue catalogue) {
        List<ProviderOption> options = catalogue.options().stream()
                .map(o -> new ProviderOption(o.provider(), PawapayProviders.label(o.provider()),
                        o.provider().equalsIgnoreCase(catalogue.detected())))
                .toList();
        return new MobileMoneyProvidersResponse(catalogue.countryAlpha2(), catalogue.currency(),
                Msisdn.mask(catalogue.msisdn()), catalogue.detected(), options);
    }
}
