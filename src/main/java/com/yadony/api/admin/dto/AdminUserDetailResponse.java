package com.yadony.api.admin.dto;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.billing.ProSubscriptionEntity;
import com.yadony.api.billing.dto.AdminProSubscriptionView;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.yadony.api.payments.hold.PayoutHoldSummary;

public record AdminUserDetailResponse(
        UUID id,
        String firstName,
        String lastName,
        String phoneNumber,
        String city,
        String country,
        String status,
        String kycStatus,
        boolean isProAccount,
        BigDecimal averageRating,
        int totalTrips,
        int totalShipments,
        LocalDateTime createdAt,
        String email,
        List<String> roles,
        String stripeAccountStatus,
        BigDecimal commissionRateOverride,
        boolean publishingSuspended,
        boolean kiloPro,
        int cancellationCount,
        int noShowCount,
        int refusedCount,
        int senderHandoverIncidentCount,
        int ratingCount,
        LocalDateTime deletionRequestedAt,
        LocalDateTime messagingMutedUntil,
        AdminProSubscriptionView proSubscription,
        /** Compte de versement mobile money (pawaPay) : le rail de paiement des voyageurs en zone CFA. */
        String mobileMoneyStatus,
        String mobileMoneyProvider,
        String mobileMoneyCurrency,
        String mobileMoneyCountry,
        String mobileMoneyMsisdnMasked,
        /** Debut du plus ancien gel actif des versements (V270) ; {@code null} si non gele. */
        LocalDateTime payoutsHeldSince,
        /** Motif principal du gel : {@code BANNED} ou {@code KYC_REVOKED} ; {@code null} si non gele. */
        String payoutsHeldReason,
        /** Tous les motifs actifs (les deux peuvent coexister) ; vide si non gele. */
        List<String> payoutsHeldReasons,
        /** Paiements ESCROW retenus a la livraison pour ce voyageur, a liberer a la main. */
        long heldPaymentsCount,
        /**
         * Finalisation prevue d'une demande de suppression ({@code deletionRequestedAt} + delai de
         * grace) ; {@code null} sans demande en cours. Le scheduler tourne a 2 h : la finalisation
         * effective survient au premier passage apres cette date.
         */
        LocalDateTime deletionScheduledFor,
        /**
         * UID Firebase du compte, pour que l'admin le copie (support, recherche
         * {@code GET /admin/users?query=}). Détail seulement : la liste ne l'expose pas.
         */
        String firebaseUid
) {
    /**
     * Téléphone et email proviennent de Firebase : ils ne sont plus stockés en base.
     * Charge en plus l'état d'abonnement PRO pour l'administration.
     */
    public static AdminUserDetailResponse from(UserEntity u, FirebaseContactService.Contact contact,
                                                ProSubscriptionEntity sub) {
        return from(u, contact, sub, PayoutHoldSummary.NONE);
    }

    public static AdminUserDetailResponse from(UserEntity u, FirebaseContactService.Contact contact,
                                                ProSubscriptionEntity sub, PayoutHoldSummary payoutHold) {
        PayoutHoldSummary hold = payoutHold != null ? payoutHold : PayoutHoldSummary.NONE;
        return new AdminUserDetailResponse(
                u.getId(),
                u.getFirstName(),
                u.getLastName(),
                contact.phoneNumber(),
                u.getCity(),
                u.getCountry(),
                u.getStatus().name(),
                u.getKycStatus().name(),
                u.isProAccount(),
                u.getAverageRating(),
                u.getTotalTrips(),
                u.getTotalShipments(),
                u.getCreatedAt(),
                contact.email(),
                u.getRoles().stream().map(Enum::name).toList(),
                u.getStripeAccountStatus() != null ? u.getStripeAccountStatus().name() : null,
                u.getCommissionRateOverride(),
                u.isPublishingSuspended(),
                u.isKiloPro(),
                u.getCancellationCount(),
                u.getNoShowCount(),
                u.getRefusedCount(),
                u.getSenderHandoverIncidentCount(),
                u.getRatingCount(),
                u.getDeletionRequestedAt() != null
                        ? java.time.LocalDateTime.ofInstant(u.getDeletionRequestedAt(), java.time.ZoneOffset.UTC)
                        : null,
                u.getMessagingMutedUntil() != null
                        ? java.time.LocalDateTime.ofInstant(u.getMessagingMutedUntil(), java.time.ZoneOffset.UTC)
                        : null,
                AdminProSubscriptionView.from(sub),
                u.getMobileMoneyStatus() != null ? u.getMobileMoneyStatus().name() : null,
                u.getMobileMoneyProvider(),
                u.getMobileMoneyCurrency(),
                u.getMobileMoneyCountry(),
                u.getMobileMoneyMsisdnMasked(),
                hold.heldSince(),
                hold.primaryReason() != null ? hold.primaryReason().name() : null,
                hold.reasons() == null ? List.of() : hold.reasons().stream().map(Enum::name).toList(),
                hold.heldPaymentsCount(),
                toUtc(com.yadony.api.auth.AccountDeletionScheduler.scheduledFinalization(u.getDeletionRequestedAt())),
                u.getFirebaseUid()
        );
    }

    private static LocalDateTime toUtc(java.time.Instant instant) {
        return instant != null ? LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC) : null;
    }
}
