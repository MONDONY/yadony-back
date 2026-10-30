package com.yadony.api.tracking;

import java.util.Optional;
import java.util.UUID;

/**
 * Raison de refuser le scan de remise (DEPART) d'un colis encore ACCEPTED, déclarée par un autre
 * package (supplément de prix en attente : {@code payments/supplement} ; modification du colis :
 * {@code payments/amendment} ; déclaration d'absence de l'expéditeur : {@code cancellation}). Port
 * appelé sous le verrou du colis, après la branche idempotente du DEPART déjà enregistré, dans la
 * transaction du scan : une porte peut y clore ce que la remise rend caduc (supplément échu,
 * déclaration d'absence en attente), et un refus ou un échec du scan l'annule avec lui.
 */
public interface HandoverBlocker {

    /** Code RFC 7807 du refus (409), ou vide si rien ne bloque. */
    Optional<String> blockingCode(UUID bidId);

    /**
     * Variante appelée par le scan, avec le voyageur qui scanne ({@code actorId}) pour l'audit de ce
     * que la porte clôt. Par défaut, l'auteur est ignoré.
     */
    default Optional<String> blockingCode(UUID bidId, UUID actorId) {
        return blockingCode(bidId);
    }

    /**
     * Clé i18n du détail du refus (traduit dans la langue de la requête), ou {@code null} pour le
     * message générique « demande en cours (supplément de prix ou modification) ».
     */
    default String messageKey() {
        return null;
    }
}
