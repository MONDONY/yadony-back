package com.yadony.api.tracking.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

public record TrackingSearchResponse(
        String trackingNumber,
        UUID bidId,
        String departureCity,
        String arrivalCity,
        String currentStep,
        String stepLabel,
        String paymentStatus,
        String arrivalInstructions,
        /**
         * Mode du trajet qui porte le colis ({@code PLANE}, {@code BOAT}, {@code CAR}...), servi tel quel :
         * l'app en tire l'icône et les libellés d'un numéro de suivi qu'elle ne connaît pas encore. Toujours
         * sérialisé, null compris (trajet sans mode connu) : jamais de repli sur l'avion côté serveur.
         */
        @JsonInclude(JsonInclude.Include.ALWAYS) String transportMode,
        /**
         * Moment où l'argent part vers le transporteur ({@code DELIVERY} ou {@code DEPOSIT}, figé à l'acceptation),
         * pour l'écran de scan qui retrouve le colis par son numéro : un colis {@code DEPOSIT} exige une photo au
         * dépôt (422 {@code boat-deposit-photo-required}). Toujours sérialisé ; absent ou inconnu se lit
         * {@code DELIVERY}.
         */
        @JsonInclude(JsonInclude.Include.ALWAYS) String payoutTrigger
) {}
