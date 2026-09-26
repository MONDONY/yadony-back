package com.yadony.api.messaging;

/**
 * Messages écrits par la plateforme elle-même dans une conversation.
 *
 * <p>Le sentinelle {@link #SENDER_ID} voyage jusqu'à la Cloud Function, qui la relaie au
 * backend comme {@code senderFirebaseUid} : trois endroits la comparaient chacun à son propre
 * littéral. Un seul ici, pour qu'un changement de valeur ne laisse pas un des trois derrière.
 */
public final class SystemMessages {

    /** Valeur du champ {@code senderId} d'un message posté par la plateforme. */
    public static final String SENDER_ID = "SYSTEM";

    private SystemMessages() {}

    /** Vrai si cet identifiant d'expéditeur désigne la plateforme et non une personne. */
    public static boolean isSystemSender(String senderId) {
        return SENDER_ID.equals(senderId);
    }
}
