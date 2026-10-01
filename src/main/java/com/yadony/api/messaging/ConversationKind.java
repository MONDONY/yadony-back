package com.yadony.api.messaging;

/**
 * Type d'une conversation (colonne {@code conversations.kind}, V283).
 *
 * <p>Les deux types partagent les colonnes {@code sender_id} / {@code traveler_id} et les
 * champs Firestore {@code senderId} / {@code travelerId} : la Cloud Function des non-lus et
 * les règles Firestore ne connaissent que ces deux participants et ne changent pas. Seul le
 * sens du participant A ({@code sender_id}) dépend du type, voir
 * {@link ConversationEntity#participantAId()}.
 */
public enum ConversationKind {
    /** Expéditeur du colis ↔ voyageur. Une par bid, ouverte à l'acceptation. */
    SENDER_TRAVELER,
    /**
     * Destinataire rattaché (lien CONFIRMED) ↔ voyageur, invisible pour l'expéditeur.
     * {@code sender_id} y porte le DESTINATAIRE. Fermée quand le destinataire change.
     */
    RECIPIENT_TRAVELER
}
