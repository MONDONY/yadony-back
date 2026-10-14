package com.yadony.api.referral.events;

import java.util.UUID;

/**
 * Publié par {@code DeliveryConfirmedReferralListener} quand le premier envoi d'un filleul
 * est livré : la récompense du parrain devient disponible et il doit la choisir.
 * Le paquet {@code notifications} prévient le parrain.
 *
 * @param invitationId       invitation dont la récompense est disponible.
 * @param referrerUserId     le parrain à prévenir.
 * @param refereeDisplayName prénom + initiale du filleul ({@code null} si inconnu), jamais
 *                           son téléphone.
 */
public record ReferralRewardAvailableEvent(UUID invitationId, UUID referrerUserId, String refereeDisplayName) {
}
