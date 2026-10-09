package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

public record BidResponse(
        UUID id,
        UUID announcementId,
        UUID senderId,
        String senderName,
        /**
         * Le numéro de l'expéditeur est joignable : le client peut afficher le bouton
         * d'appel. Le numéro lui-même s'obtient via {@code GET /bids/{id}/contact}, au
         * moment du tap — il ne voyage pas dans les réponses de liste.
         */
        boolean senderPhoneAvailable,
        Integer senderTotalShipments,
        boolean senderKycVerified,
        boolean senderIsProAccount,
        boolean senderKiloPro,
        BigDecimal weightKg,
        String description,
        String contentCategory,
        String recipientName,
        String recipientPhone,
        String status,
        String rejectionReason,
        String handoverLocation,
        LocalDateTime handoverDeadline,
        boolean voyageurConfirmed,
        LocalDateTime disclaimerSignedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        LocalTime departureTime,
        LocalTime arrivalTime,
        /** Date d'arrivée du trajet si différente du départ ; null = même jour. */
        java.time.LocalDate arrivalDate,
        BigDecimal pricePerKg,
        /** Tarif/kg BRUT affiché à l'expéditeur (net + commission). L'expéditeur
         * ne reçoit jamais le tarif net {@code pricePerKg}. */
        BigDecimal pricePerKgSenderEur,
        com.yadony.api.matching.TransportMode transportMode,
        String trackingNumber,
        String trackingToken,
        String confirmationCode,
        boolean confirmationCodePublicEnabled,
        UUID travelerId,
        String travelerName,
        /** Idem {@code senderPhoneAvailable}, côté voyageur. */
        boolean travelerPhoneAvailable,
        boolean travelerKycVerified,
        boolean travelerIsProAccount,
        boolean travelerKiloPro,
        Integer travelerTotalTrips,
        java.math.BigDecimal travelerAverageRating,
        boolean senderHasRated,
        boolean travelerHasRated,
        Integer confirmationCodeRefreshCount,
        LocalDateTime confirmationCodeRefreshWindowStart,
        String cancellationNoShowStatus,
        java.time.OffsetDateTime contestationDeadline,
        String deliveryNoShowStatus,
        java.time.OffsetDateTime deliveryNoShowContestationDeadline,
        Boolean deliveryNoShowReportedByTraveler,
        String paymentMethod,
        com.yadony.api.matching.BidPricingMode pricingMode,
        BigDecimal totalNetAmountEur,
        BigDecimal totalSenderAmountEur,
        java.time.OffsetDateTime departureAt,
        String returnCode,
        java.time.LocalDateTime returnDeadline,
        java.time.LocalDateTime returnedAt,
        String senderAvatarUrl,
        String travelerAvatarUrl,
        java.util.List<com.yadony.api.matching.dto.BidPhotoResponse> photos,
        /** ID de la {@code CancellationEntity} ouvrant droit au rematch (trajet annulé,
         * transport annulé/refusé par le voyageur, retrait après report ou annulation après
         * remise) — distinct des cancellations no-show, qui n'ouvrent pas droit au rematch.
         * Null si le bid n'a pas été affecté par une cancellation rematch. */
        UUID tripCancellationId,
        /** {@code rematchStatus} de cette cancellation ("NONE" / "SUGGESTED") — permet au
         * front d'afficher le CTA « Voir les trajets alternatifs ». Null si pas de
         * cancellation ouvrant droit au rematch pour ce bid. */
        String tripCancellationRematchStatus,
        String currency,
        String arrivalInstructions,
        /** Dernier report du trajet (vol annulé, voyage repoussé) et la réponse attendue de
         * l'expéditeur. Null si le trajet n'a jamais été reporté. */
        TripRescheduleInfo reschedule,
        /** Réponse du destinataire dont le compte est rattaché au colis : PENDING,
         * CONFIRMED ou DECLINED. L'expéditeur la reçoit toujours ; le voyageur ne reçoit
         * que CONFIRMED (null sinon) : un refus lui est signalé par
         * {@code recipientDeclined}. */
        String recipientAppStatus,
        /** Vue voyageur : le destinataire a masqué son numéro ({@code recipientPhone} est
         * alors null) et se joint par la messagerie de l'app. Toujours false pour
         * l'expéditeur. */
        boolean recipientPhoneHidden,
        /** Lieu de remise du colis au voyageur (adresse de départ du trajet), avec
         * ses coordonnées pour l'ouvrir dans l'app de cartes. Null si le trajet est
         * introuvable. */
        AddressDto handoverAddress,
        /** Lieu où le destinataire récupère le colis à l'arrivée (adresse d'arrivée
         * du trajet). Même visibilité que {@code arrivalInstructions} : null pour une
         * demande sortie de la course. */
        AddressDto deliveryAddress,
        /** Vue voyageur : le destinataire a refusé le colis ou s'en est retiré (lien
         * DECLINED). {@code recipientName} et {@code recipientPhone} sont alors null ; le
         * voyageur peut demander à l'expéditeur d'en désigner un autre
         * ({@code POST /bids/{id}/recipient/replacement-request}). Toujours false pour
         * l'expéditeur, qui lit {@code recipientAppStatus}. */
        boolean recipientDeclined,
        /** Dernière demande de remplacement faite par le voyageur depuis le refus courant
         * (UTC), null sinon. Une nouvelle demande est possible 12 h après. Servie à
         * l'expéditeur et au voyageur tant que le lien est DECLINED. */
        java.time.OffsetDateTime recipientReplacementRequestedAt,
        /** Expéditeur et voyageur peuvent encore se joindre directement (bouton téléphone de
         * la fiche colis) : même règle que l'appel in-app — de l'acceptation à l'arrivée, puis
         * {@code yadony.calls.delivery-grace-days} jours après la livraison confirmée
         * ({@link com.yadony.api.matching.ContactWindow}). Identique pour les deux parties. Ne
         * dit rien du numéro : sa révélation garde sa propre règle ({@code *PhoneAvailable}). */
        boolean contactWindowOpen,
        /** Fiabilité de l'expéditeur, vue par le voyageur qui juge la demande (FLUTTER-E0/E6) :
         * annulations après acceptation et absences au rendez-vous de remise confirmées.
         * Null si l'expéditeur est introuvable. */
        Integer senderIncidentCount,
        /** Colis chez le voyageur (HANDED_OVER, IN_TRANSIT, ARRIVED) dont le code de retrait
         * a été effacé (trop d'essais faux) ou a expiré. Servi aux deux parties, le code
         * lui-même restant réservé à l'expéditeur : l'expéditeur le régénère
         * ({@code POST /tracking/{bidId}/refresh-code}), le voyageur le lui demande
         * ({@code POST /tracking/{bidId}/request-code}, FLUTTER-G2). */
        boolean pickupCodeRenewalNeeded
) {}
