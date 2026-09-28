package com.yadony.api.auth.events;

import java.util.UUID;

/**
 * Compte banni par un administrateur ({@code UserService#banUser}).
 *
 * <p>Publie dans la transaction du bannissement. Un ecouteur synchrone y participe : le gel des
 * versements du voyageur ({@code payments/hold}) est ainsi commite en meme temps que le statut
 * BANNED, sans fenetre pendant laquelle une livraison pourrait encore le payer.
 *
 * <p>La finalisation d'un compte (suppression RGPD) pose aussi BANNED mais ne publie PAS cet
 * evenement : elle a son propre traitement ({@link UserFinalizedEvent}).
 */
public record UserBannedEvent(UUID userId, String reason, UUID adminId) {
}
