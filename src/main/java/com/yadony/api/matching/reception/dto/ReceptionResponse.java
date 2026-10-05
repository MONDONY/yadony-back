package com.yadony.api.matching.reception.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Un colis attendu par l'utilisateur courant, vu depuis son compte de destinataire.
 *
 * <p>Tant que {@code linkStatus} vaut {@code PENDING}, seuls l'expéditeur (prénom, profil), le
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
        String travelerAvatarUrl,
        /**
         * Expéditeur : ouvre son profil public (FLUTTER-7P). Servi dès PENDING, comme son
         * prénom : le profil public est visible de tous et aide à reconnaître le colis.
         */
        UUID senderId,
        /** Avatar de l'expéditeur (URL servie). Null s'il n'en a pas. */
        String senderAvatarUrl,
        /**
         * Le destinataire peut noter le voyageur (FLUTTER-CA) : lien confirmé, colis livré,
         * et aucune note destinataire sur ce colis (depuis le compte ou le lien de suivi).
         */
        boolean canRate,
        /** Étoiles de la note laissée depuis ce compte, null s'il n'a pas noté. */
        Integer myRating
) {}
