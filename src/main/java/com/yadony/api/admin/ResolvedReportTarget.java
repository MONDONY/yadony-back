package com.yadony.api.admin;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.ratings.RatingEntity;

/**
 * Cible d'un signalement relue au moment de l'affichage ou de l'action.
 *
 * @param targetFound      la cible existe encore (compte, annonce, demande, offre, avis, message)
 * @param alreadyModerated message déjà supprimé, ou avis déjà exclu de la moyenne
 * @param author           auteur du contenu signalé, {@code null} s'il est inconnu, anonyme ou supprimé
 * @param conversation     conversation du message (cible MESSAGE seulement)
 * @param messageId        identifiant Firestore du message (cible MESSAGE seulement)
 * @param rating           avis signalé (cible RATING seulement), pour l'aperçu de l'admin
 * @param ratedUser        compte noté par cet avis, {@code null} s'il est supprimé ou inconnu
 */
public record ResolvedReportTarget(
        boolean targetFound,
        boolean alreadyModerated,
        UserEntity author,
        ConversationEntity conversation,
        String messageId,
        RatingEntity rating,
        UserEntity ratedUser
) {
    private static final ResolvedReportTarget NONE = new ResolvedReportTarget(false, false, null, null, null);

    /** Cible sans avis (tout type sauf RATING). */
    public ResolvedReportTarget(boolean targetFound, boolean alreadyModerated, UserEntity author,
                                ConversationEntity conversation, String messageId) {
        this(targetFound, alreadyModerated, author, conversation, messageId, null, null);
    }

    public static ResolvedReportTarget none() {
        return NONE;
    }

    public boolean messageResolved() {
        return targetFound && conversation != null && messageId != null;
    }
}
