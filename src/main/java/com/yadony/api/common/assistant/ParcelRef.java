package com.yadony.api.common.assistant;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Colis accessible au demandeur de l'assistant, vu depuis son rôle. Jamais de nom, de
 * téléphone ni de code : seulement ce que l'écran du colis lui montre déjà.
 *
 * @param userId         le demandeur (pas forcément l'expéditeur)
 * @param status         nom du {@code BidStatus} : {@code common} ne dépend pas de {@code matching}
 * @param trackingNumber numéro DON-… s'il est visible pour ce rôle, sinon {@code null}
 * @param active         colis en cours (ni livré, ni annulé, ni clos)
 */
public record ParcelRef(UUID bidId, UUID userId, ParcelRole role, String status, String trackingNumber,
                        String departureCity, String arrivalCity, LocalDate departureDate, boolean active) {
}
