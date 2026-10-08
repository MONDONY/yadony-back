package com.yadony.api.common;

import java.util.Collection;
import java.util.UUID;

/**
 * Une procédure ouverte retient-elle les photos de messagerie d'un bid (FLUTTER-B4) ?
 *
 * <p>Les photos sont purgées 7 jours après la fin du bid, sauf tant qu'un litige ou un
 * signalement est ouvert : ce sont des pièces. Chaque package concerné (disputes/,
 * signalements/) implémente cette interface ; messaging/ les interroge toutes sans
 * dépendre de leurs services.
 */
public interface MessagingMediaRetentionHold {

    /**
     * @param bidId           bid dont les photos seraient purgées
     * @param conversationIds conversations de ce bid (expéditeur et destinataire)
     * @return vrai si une procédure ouverte impose de les garder
     */
    boolean holds(UUID bidId, Collection<UUID> conversationIds);
}
