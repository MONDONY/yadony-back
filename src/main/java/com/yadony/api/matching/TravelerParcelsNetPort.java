package com.yadony.api.matching;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.UUID;

/**
 * Net estimé que le voyageur recevra pour un lot de colis, vu depuis {@code matching/}
 * (résumé « colis en ma possession » de son trajet). Implémenté dans {@code payments/}
 * ({@code TravelerParcelsNetAdapter}), même découpage que {@link TravelerPayoutNetworksPort}.
 */
public interface TravelerParcelsNetPort {

    /** Aucun calcul (port absent, tests unitaires) : net nul. */
    TravelerParcelsNetPort NONE = bidIds -> BigDecimal.ZERO;

    /**
     * Somme, dans la devise des paiements, de (montant payé − commission) des colis réglés en ligne
     * et non remboursés. Les colis en espèces (sans paiement) comptent pour zéro. Lecture seule.
     */
    BigDecimal estimatedNet(Collection<UUID> bidIds);
}
