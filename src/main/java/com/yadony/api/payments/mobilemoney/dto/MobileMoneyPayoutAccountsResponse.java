package com.yadony.api.payments.mobilemoney.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Comptes de versement mobile money du voyageur (GET /payments/mobile-money/accounts), un par code
 * opérateur pawaPay. Ne porte jamais un numéro en clair : seulement sa forme masquée
 * ({@link com.yadony.api.common.Msisdn#mask(String)}).
 *
 * @param suggestedProviders réseaux cochés sur l'ancien compte unique (compte repris) qui n'ont pas
 *                           encore de compte propre : l'app invite à les ajouter (spec §6.3)
 */
public record MobileMoneyPayoutAccountsResponse(List<AccountView> accounts, List<ProviderView> suggestedProviders) {

    /**
     * Un compte de versement.
     *
     * @param pendingPayouts colis payés sur ce code qui attendent leur versement : le compte ne peut
     *                       être ni retiré ni changé de numéro tant que ce nombre n'est pas nul
     */
    public record AccountView(UUID id, String provider, String providerLabel, String brand, String country,
                              String currency, String msisdnMasked, Instant verifiedAt, int pendingPayouts) {}

    /** Un réseau : code pawaPay et libellé lisible. */
    public record ProviderView(String code, String label) {}
}
