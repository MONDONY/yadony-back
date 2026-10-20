package com.yadony.api.matching;

import java.util.List;
import java.util.UUID;

/**
 * Réseaux mobile money où un voyageur reçoit ses versements, vus depuis {@code matching/}
 * (fiche d'un trajet). Implémenté dans {@code payments/} ({@code TravelerPayoutNetworksAdapter}),
 * même découpage que {@link BidNegotiationMobileMoneyPort} : {@code matching/} ne voit rien de pawaPay.
 */
public interface TravelerPayoutNetworksPort {

    /**
     * Un réseau accepté : code opérateur ({@code ORANGE_CIV}), libellé (« Orange Money »), marque
     * ({@code ORANGE}) et pays ISO-2 ({@code CI}). Jamais de numéro.
     */
    record PayoutNetwork(String code, String label, String brand, String country) {}

    /** Réseaux des comptes vivants du voyageur dans cette devise, le plus ancien compte d'abord. */
    List<PayoutNetwork> acceptedNetworks(UUID travelerId, String currency);
}
