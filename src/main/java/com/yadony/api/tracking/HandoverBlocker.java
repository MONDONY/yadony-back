package com.yadony.api.tracking;

import java.util.Optional;
import java.util.UUID;

/**
 * Raison de refuser le scan de remise (DEPART) d'un colis encore ACCEPTED, déclarée par un autre
 * package (supplément de prix en attente : {@code payments/supplement} ; modification du colis :
 * {@code payments/amendment} ; déclaration d'absence de l'expéditeur : {@code cancellation}). Port
 * appelé sous le verrou du colis, dans la transaction du scan, en deux temps :
 * <ol>
 *   <li>{@link #blockingCode} avant l'enregistrement : la remise est-elle refusée ?</li>
 *   <li>{@link #onHandover} une fois la remise enregistrée : clore ce qu'elle rend caduc
 *       (déclaration d'absence en attente). Un échec du scan ensuite l'annule avec lui.</li>
 * </ol>
 */
public interface HandoverBlocker {

    /** Code RFC 7807 du refus (409), ou vide si rien ne bloque. */
    Optional<String> blockingCode(UUID bidId);

    /** La remise vient d'être enregistrée. Par défaut, rien à faire. */
    default void onHandover(HandoverScan scan) {
    }

    /**
     * Clé i18n du détail du refus (traduit dans la langue de la requête), ou {@code null} pour le
     * message générique « demande en cours (supplément de prix ou modification) ».
     */
    default String messageKey() {
        return null;
    }
}
