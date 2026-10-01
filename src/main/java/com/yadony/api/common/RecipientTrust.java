package com.yadony.api.common;

import java.util.UUID;

/**
 * Relation de confiance expéditeur → destinataire, établie quand le destinataire accepte
 * l'invitation de l'expéditeur à rejoindre son carnet.
 *
 * <p>Contrat exposé dans {@code common/} (comme {@link BlockVisibility}) pour que le
 * rattachement des colis ({@code matching/reception}) l'applique sans dépendre du package
 * {@code addressbook/invitation} qui en possède la table.
 */
public interface RecipientTrust {

    /** Vrai si {@code recipientUserId} a accepté l'invitation de {@code senderId}, toujours active. */
    boolean isTrusted(UUID senderId, UUID recipientUserId);
}
