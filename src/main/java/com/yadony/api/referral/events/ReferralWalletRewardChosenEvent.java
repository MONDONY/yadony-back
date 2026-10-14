package com.yadony.api.referral.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Publié quand le parrain choisit le crédit de solde comme récompense. Le paquet
 * {@code payments/wallet} crédite le portefeuille (la communication entre paquets passe
 * par des événements), une seule fois par invitation grâce à la clé d'idempotence
 * {@code referral-reward:<invitationId>}.
 *
 * @param invitationId   invitation récompensée.
 * @param referrerUserId le parrain à créditer.
 * @param amount         montant à créditer.
 * @param currency       devise du portefeuille crédité (EUR).
 */
public record ReferralWalletRewardChosenEvent(UUID invitationId, UUID referrerUserId, BigDecimal amount,
                                              String currency) {

    /** Clé d'idempotence du crédit : une invitation ne crédite jamais deux fois. */
    public String idempotencyKey() {
        return "referral-reward:" + invitationId;
    }
}
