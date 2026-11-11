package com.yadony.api.kyc;

/**
 * Le prestataire de vérification n'a pas pu répondre (réseau, délai, réponse illisible) : à réessayer. Distincte d'une
 * absence de donnée. Ne porte jamais de contenu de la décision.
 */
public class IdentityProviderUnavailableException extends RuntimeException {

    public IdentityProviderUnavailableException(String message) {
        super(message);
    }
}
