package com.yadony.api.messaging.dto;

/**
 * Réponse de {@code POST /internal/messaging/notify}.
 *
 * @param recipientFirebaseUid UID Firebase du destinataire du message, ou {@code null}
 *                             si l'expéditeur est introuvable. La Cloud Function s'en
 *                             sert pour créditer le compteur de non-lus lorsque le
 *                             document Firestore de la conversation est absent : la
 *                             base reste la source de vérité des participants.
 */
public record NotifyMessageResponse(String recipientFirebaseUid) {
}
