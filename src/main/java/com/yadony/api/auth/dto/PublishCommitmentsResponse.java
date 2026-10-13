package com.yadony.api.auth.dto;

/**
 * Engagements de publication de l'utilisateur courant.
 *
 * @param currentVersion version en vigueur du texte des engagements
 * @param traveler       engagements du voyageur (publier un trajet)
 * @param sender         engagements de l'expéditeur (publier une demande d'envoi)
 */
public record PublishCommitmentsResponse(
        String currentVersion,
        Status traveler,
        Status sender) {

    /**
     * @param acceptedVersion version acceptée, null si jamais acceptée
     * @param acceptedAt      ISO-8601, null si jamais acceptée
     * @param required        vrai tant que la version en vigueur n'est pas acceptée :
     *                        l'app montre alors l'écran des engagements avant le formulaire
     */
    public record Status(String acceptedVersion, String acceptedAt, boolean required) {}
}
