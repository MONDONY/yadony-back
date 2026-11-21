package com.yadony.api.referral.events;

import java.util.UUID;

/**
 * Publié quand un filleul saisit un code de parrainage et reçoit son code promo de bienvenue.
 * Les écouteurs (notification au filleul) s'exécutent après la validation de la transaction.
 *
 * @param refereeUserId     le filleul, propriétaire du code promo.
 * @param referrerUserId    le parrain.
 * @param referrerFirstName prénom du parrain (jamais le nom ni le téléphone), peut être null.
 * @param promoCode         code promo de bienvenue créé.
 */
public record ReferralWelcomeCodeCreatedEvent(UUID refereeUserId, UUID referrerUserId,
                                              String referrerFirstName, String promoCode) {
}
