package com.yadony.api.messaging.dto;

public record ParticipantDTO(
        String id,
        String name,
        String avatarUrl,
        /**
         * L'interlocuteur est joignable par téléphone (deal actif) : le client peut
         * afficher son bouton d'appel. Le numéro s'obtient au tap via
         * {@code GET /bids/{bidId}/contact} — il ne voyage pas dans les conversations.
         */
        boolean phoneAvailable,
        /** Rôle relatif à la conversation : "Voyageur" | "Expéditeur" | "Destinataire". */
        String role,
        boolean kycVerified
) {}
