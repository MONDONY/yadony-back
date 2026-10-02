package com.yadony.api.matching.reception.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Un colis attendu par l'utilisateur courant, vu depuis son compte de destinataire.
 *
 * <p>Tant que {@code linkStatus} vaut {@code PENDING}, seuls l'expéditeur (prénom), le
 * trajet et le nom saisi sont servis : de quoi reconnaître le colis, rien de plus. Le
 * code de retrait n'apparaît qu'une fois le colis confirmé et remis au voyageur.
 */
public record ReceptionResponse(
        UUID bidId,
        String linkStatus,
        String bidStatus,
        String senderFirstName,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        LocalDate arrivalDate,
        String recipientName,
        String trackingNumber,
        String travelerFirstName,
        String arrivalInstructions,
        BigDecimal weightKg,
        String confirmationCode,
        Instant updatedAt,
        /** Voyageur, une fois le colis confirmé : ouvre son profil public. Null sinon. */
        UUID travelerId,
        /** Avatar du voyageur (URL servie), une fois le colis confirmé. Null sinon. */
        String travelerAvatarUrl
) {}
