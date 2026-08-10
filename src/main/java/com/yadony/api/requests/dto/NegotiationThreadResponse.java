package com.yadony.api.requests.dto;

import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record NegotiationThreadResponse(
    UUID id, UUID packageRequestId, UUID travelerId,
    UUID travelerAnnouncementId, LocalDate travelerTravelDate, BigDecimal travelerAvailableKg,
    String travelerCapacityUnit,
    NegotiationThreadStatus status, BigDecimal currentPriceEur, int roundsCount,
    LocalDateTime lastActivityAt, LocalDateTime createdAt,
    List<NegotiationMessageResponse> messages,
    String paymentIntentClientSecret,
    // Profil voyageur embarqué
    String travelerName, BigDecimal travelerRating, Integer travelerTripsCount, String travelerPhotoUrl,
    // Infos demande embarquées
    String departureCity, String arrivalCity, BigDecimal weightKg,
    // Profil expéditeur embarqué (affiché côté voyageur)
    String senderName,
    String senderPhotoUrl,
    // Champs calculés selon le callerId — source de vérité unique pour les clients
    boolean isMyTurn,
    boolean canAccept,
    boolean canCounter,
    int roundsRemaining,
    // Détails du trajet lié (null si aucun trajet lié)
    LinkedTripSummary linkedTrip,
    // Modèle B : prix brut (TTC commission) affiché à l'expéditeur
    BigDecimal grossPriceEur,
    // Méthode de paiement choisie pour ce thread (null jusqu'à la sélection)
    PaymentMethod paymentMethod,
    // Bid matérialisé après acceptation (null tant que non matérialisé) — permet
    // au mobile d'ouvrir le détail du bid (suivi, no-show…) depuis le thread
    UUID materializedBidId,
    // Vrai si le voyageur peut payer la commission Yadony en cash (wallet suffisant ou carte enregistrée).
    // Sert à masquer l'option CASH côté sender et traveler si elle n'est pas viable.
    boolean cashCommissionAvailable,
    // SET des modes de paiement effectivement fournissables (colis.acceptedPaymentMethods ∩
    // capacité voyageur), calculé au trip-linking. Null tant qu'aucun trajet n'est lié.
    Set<PaymentMethod> availablePaymentMethods,
    // Vrai si le viewer peut relancer l'autre partie (thread OPEN/AWAITING_TRIP, ce n'est pas
    // son tour, attente > 1h depuis la dernière activité, et pas de relance déjà envoyée < 1h).
    boolean canNudge,
    // Une réponse de l'autre participant est arrivée depuis la dernière ouverture du fil.
    boolean hasUnread,
    // Code promo auto-porté depuis la demande (PackageRequestEntity.promoCode) dès la
    // création du thread — appliqué automatiquement au paiement, jamais resaisi. Null
    // si l'expéditeur n'en avait pas renseigné à la publication.
    String promoCode,
    // Snapshot serveur du taux réellement appliqué. Null avant la première
    // initiation de paiement réussie.
    BigDecimal commissionRate,
    // Devise persistée sur le thread, source serveur du paiement de négociation.
    String currency
) {
    /** Constructeur de compatibilité (sans promoCode) — évite de retoucher tous les tests. */
    public NegotiationThreadResponse(
            UUID id, UUID packageRequestId, UUID travelerId,
            UUID travelerAnnouncementId, LocalDate travelerTravelDate, BigDecimal travelerAvailableKg,
            String travelerCapacityUnit,
            NegotiationThreadStatus status, BigDecimal currentPriceEur, int roundsCount,
            LocalDateTime lastActivityAt, LocalDateTime createdAt,
            List<NegotiationMessageResponse> messages,
            String paymentIntentClientSecret,
            String travelerName, BigDecimal travelerRating, Integer travelerTripsCount, String travelerPhotoUrl,
            String departureCity, String arrivalCity, BigDecimal weightKg,
            String senderName,
            String senderPhotoUrl,
            boolean isMyTurn,
            boolean canAccept,
            boolean canCounter,
            int roundsRemaining,
            LinkedTripSummary linkedTrip,
            BigDecimal grossPriceEur,
            PaymentMethod paymentMethod,
            UUID materializedBidId,
            boolean cashCommissionAvailable,
            Set<PaymentMethod> availablePaymentMethods,
            boolean canNudge,
            boolean hasUnread) {
        this(id, packageRequestId, travelerId, travelerAnnouncementId, travelerTravelDate, travelerAvailableKg,
            travelerCapacityUnit, status, currentPriceEur, roundsCount, lastActivityAt, createdAt, messages,
            paymentIntentClientSecret, travelerName, travelerRating, travelerTripsCount, travelerPhotoUrl,
            departureCity, arrivalCity, weightKg, senderName, senderPhotoUrl, isMyTurn, canAccept, canCounter,
            roundsRemaining, linkedTrip, grossPriceEur, paymentMethod, materializedBidId, cashCommissionAvailable,
            availablePaymentMethods, canNudge, hasUnread, null, null, "EUR");
    }

    /** Constructeur de compatibilité (sans currency) — les anciens appelants restent en EUR. */
    public NegotiationThreadResponse(
            UUID id, UUID packageRequestId, UUID travelerId,
            UUID travelerAnnouncementId, LocalDate travelerTravelDate, BigDecimal travelerAvailableKg,
            String travelerCapacityUnit,
            NegotiationThreadStatus status, BigDecimal currentPriceEur, int roundsCount,
            LocalDateTime lastActivityAt, LocalDateTime createdAt,
            List<NegotiationMessageResponse> messages,
            String paymentIntentClientSecret,
            String travelerName, BigDecimal travelerRating, Integer travelerTripsCount, String travelerPhotoUrl,
            String departureCity, String arrivalCity, BigDecimal weightKg,
            String senderName,
            String senderPhotoUrl,
            boolean isMyTurn,
            boolean canAccept,
            boolean canCounter,
            int roundsRemaining,
            LinkedTripSummary linkedTrip,
            BigDecimal grossPriceEur,
            PaymentMethod paymentMethod,
            UUID materializedBidId,
            boolean cashCommissionAvailable,
            Set<PaymentMethod> availablePaymentMethods,
            boolean canNudge,
            boolean hasUnread,
            String promoCode) {
        this(id, packageRequestId, travelerId, travelerAnnouncementId, travelerTravelDate, travelerAvailableKg,
            travelerCapacityUnit, status, currentPriceEur, roundsCount, lastActivityAt, createdAt, messages,
            paymentIntentClientSecret, travelerName, travelerRating, travelerTripsCount, travelerPhotoUrl,
            departureCity, arrivalCity, weightKg, senderName, senderPhotoUrl, isMyTurn, canAccept, canCounter,
            roundsRemaining, linkedTrip, grossPriceEur, paymentMethod, materializedBidId, cashCommissionAvailable,
            availablePaymentMethods, canNudge, hasUnread, promoCode, null, "EUR");
    }

    /** Constructeur de compatibilité avec le contrat Task 9 round 1. */
    public NegotiationThreadResponse(
            UUID id, UUID packageRequestId, UUID travelerId,
            UUID travelerAnnouncementId, LocalDate travelerTravelDate, BigDecimal travelerAvailableKg,
            String travelerCapacityUnit,
            NegotiationThreadStatus status, BigDecimal currentPriceEur, int roundsCount,
            LocalDateTime lastActivityAt, LocalDateTime createdAt,
            List<NegotiationMessageResponse> messages,
            String paymentIntentClientSecret,
            String travelerName, BigDecimal travelerRating, Integer travelerTripsCount, String travelerPhotoUrl,
            String departureCity, String arrivalCity, BigDecimal weightKg,
            String senderName,
            String senderPhotoUrl,
            boolean isMyTurn,
            boolean canAccept,
            boolean canCounter,
            int roundsRemaining,
            LinkedTripSummary linkedTrip,
            BigDecimal grossPriceEur,
            PaymentMethod paymentMethod,
            UUID materializedBidId,
            boolean cashCommissionAvailable,
            Set<PaymentMethod> availablePaymentMethods,
            boolean canNudge,
            boolean hasUnread,
            String promoCode,
            String currency) {
        this(id, packageRequestId, travelerId, travelerAnnouncementId, travelerTravelDate, travelerAvailableKg,
            travelerCapacityUnit, status, currentPriceEur, roundsCount, lastActivityAt, createdAt, messages,
            paymentIntentClientSecret, travelerName, travelerRating, travelerTripsCount, travelerPhotoUrl,
            departureCity, arrivalCity, weightKg, senderName, senderPhotoUrl, isMyTurn, canAccept, canCounter,
            roundsRemaining, linkedTrip, grossPriceEur, paymentMethod, materializedBidId, cashCommissionAvailable,
            availablePaymentMethods, canNudge, hasUnread, promoCode, null, currency);
    }
}
