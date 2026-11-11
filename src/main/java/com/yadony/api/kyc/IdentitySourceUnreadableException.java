package com.yadony.api.kyc;

/**
 * La pièce vérifiée ne pourra pas être relue (session effacée chez le prestataire, accès refusé, réponse illisible) :
 * erreur PERMANENTE, contrairement à {@link IdentityProviderUnavailableException}. Ne porte aucun contenu.
 */
public class IdentitySourceUnreadableException extends RuntimeException {

    public IdentitySourceUnreadableException(String message) {
        super(message);
    }
}
