package com.yadony.api.assistant.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Colis proposé à l'utilisateur quand sa question porte sur l'un des siens : l'app l'affiche en
 * bouton et renvoie la question avec son {@code parcelId}.
 *
 * @param trackingNumber numéro DON-… s'il est visible pour son rôle, sinon {@code null}
 * @param status         nom du {@code BidStatus}
 * @param role           {@code SENDER}, {@code TRAVELER} ou {@code RECIPIENT}
 */
public record AssistantParcelChoice(UUID parcelId, String trackingNumber, String departureCity,
                                    String arrivalCity, LocalDate departureDate, String status, String role,
                                    boolean active) {
}
