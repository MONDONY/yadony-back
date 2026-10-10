package com.yadony.api.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Fiche colis du back-office.
 *
 * <p>Les champs après {@code currency} ont été ajoutés pour la fiche détaillée (trajet,
 * personnes, argent, liens) : toujours en fin de record pour garder le contrat compatible, et
 * chacun peut être {@code null} quand l'information n'existe pas (colis sans paiement, voyageur
 * supprimé…). Aucun secret : le code de remise n'est jamais exposé, seulement sa présence, et
 * les téléphones sont masqués.
 */
public record AdminBidDetailResponse(
    UUID id,
    String status,
    UUID announcementId,
    String senderName,
    String travelerName,
    String corridor,
    BigDecimal weightKg,
    BigDecimal netEur,
    String paymentMethod,
    LocalDateTime createdAt,
    String contentCategory,
    String recipientName,
    String trackingNumber,
    BigDecimal commissionRate,
    String refusalReason,
    /** Devise de netEur, celle de l'annonce (code ISO en majuscules). */
    String currency,
    /** Description libre du contenu saisie par l'expéditeur. */
    String description,
    /** Annonce (trajet) du colis ; {@code null} si l'annonce n'existe plus. */
    Trip trip,
    Party sender,
    Party traveler,
    Recipient recipient,
    /** Paiement du colis (direct ou via le fil de négociation) ; {@code null} sans paiement. */
    Money money,
    Links links,
    /** Un code de remise existe (sa valeur n'est jamais exposée). */
    Boolean confirmationCodePresent,
    /** URL présignées (durée courte) des photos actives du colis. */
    List<String> photoUrls,
    Milestones milestones
) {

    /** Trajet : l'annonce du voyageur à laquelle le colis est rattaché. */
    public record Trip(
        UUID announcementId,
        String status,
        String departureCity,
        String arrivalCity,
        String departureCountryCode,
        String arrivalCountryCode,
        LocalDate departureDate,
        LocalTime departureTime,
        OffsetDateTime departureAt,
        LocalDate arrivalDate,
        LocalTime arrivalTime,
        String timezone,
        String pickupAddressLabel,
        String deliveryAddressLabel,
        String transportMode,
        BigDecimal totalKg,
        BigDecimal availableKg,
        BigDecimal reservedKg,
        String capacityUnit,
        BigDecimal pricePerKg,
        UUID tripGroupId,
        Integer tripLegIndex,
        LocalDateTime handoverDeadline,
        /** Autres colis (non supprimés) sur la même annonce. */
        long otherBidsCount
    ) {}

    /** Expéditeur ou voyageur. Les champs de versement ne concernent que le voyageur. */
    public record Party(
        UUID id,
        String name,
        String username,
        String phoneMasked,
        String status,
        String kycStatus,
        String stripeAccountStatus,
        /** Compte Stripe Connect utilisable pour un versement (onboarding terminé). */
        Boolean stripeConnectUsable,
        String mobileMoneyStatus,
        Boolean mobileMoneyUsable
    ) {}

    public record Recipient(String name, String phoneMasked) {}

    public record Money(
        UUID paymentId,
        String status,
        String rail,
        long amountCents,
        long commissionCents,
        long refundedCents,
        String currency,
        Instant capturedAt,
        LocalDateTime escrowReleasedAt,
        LocalDateTime payoutHeldAt,
        boolean disputed
    ) {}

    public record Links(
        UUID negotiationThreadId,
        UUID disputeId,
        String disputeStatus,
        /** Identifiant Firestore de la conversation (celui de la modération). */
        String conversationId,
        UUID cancellationId
    ) {}

    /** Dates clés portées par le colis lui-même. */
    public record Milestones(
        String handoverLocation,
        LocalDateTime handoverDeadline,
        LocalDateTime arrivedAt,
        LocalDateTime deliveredAt,
        LocalDateTime noShowAt,
        LocalDateTime returnedAt
    ) {}
}
