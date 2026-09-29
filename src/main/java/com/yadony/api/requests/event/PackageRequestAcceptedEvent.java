package com.yadony.api.requests.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record PackageRequestAcceptedEvent(
    UUID threadId, UUID packageRequestId,
    UUID senderId, UUID travelerId,
    BigDecimal agreedPriceEur,
    UUID travelerAnnouncementId,
    BigDecimal weightKg,
    String description,
    String contentCategory,
    String paymentIntentId,
    String recipientName,
    String recipientPhone,
    LocalDateTime disclaimerSignedAt,
    String disclaimerSignedIp,
    com.yadony.api.payments.cash.PaymentMethod paymentMethod,
    /** Clés S3 des photos colis de la demande (package_requests/…) à copier vers le bid. */
    java.util.List<String> photoObjectKeys,
    /**
     * "WALLET"/"CARD" si la commission Yadony a été prélevée au voyageur pour ce thread
     * CASH (cf. {@code NegotiationThreadEntity.commissionChargedVia}), null sinon
     * (STRIPE — pas de prélèvement commission ici). Permet au bid matérialisé de savoir
     * comment rembourser si le bid est annulé avant remise (cf. BidCancelledCommissionRefundListener).
     */
    String commissionChargedVia,
    /**
     * Code promo appliqué par l'expéditeur au paiement (null si aucun) et taux de
     * commission effectivement retenu — stampés sur le thread par
     * {@code NegotiationService.recordAppliedPromo}. Consommés par
     * {@code ThreadAcceptedBidListener} pour déclencher le rachat (redeem) une fois
     * le bid matérialisé, seul point où un bid_id existe pour la contrainte
     * UNIQUE(promo_code_id, bid_id) de {@code promo_redemptions}.
     */
    String promoCode,
    BigDecimal commissionRate,
    /** Devise du fil : {@code agreedPriceEur} (net) est exprimé dans cette devise, pas en euros. */
    String currency
) {
    /** Sans devise : euros (anciens appelants de test). */
    public PackageRequestAcceptedEvent(UUID threadId, UUID packageRequestId, UUID senderId,
                                       UUID travelerId, BigDecimal agreedPriceEur,
                                       UUID travelerAnnouncementId, BigDecimal weightKg,
                                       String description, String contentCategory,
                                       String paymentIntentId, String recipientName,
                                       String recipientPhone, LocalDateTime disclaimerSignedAt,
                                       String disclaimerSignedIp,
                                       com.yadony.api.payments.cash.PaymentMethod paymentMethod,
                                       java.util.List<String> photoObjectKeys,
                                       String commissionChargedVia, String promoCode,
                                       BigDecimal commissionRate) {
        this(threadId, packageRequestId, senderId, travelerId, agreedPriceEur,
                travelerAnnouncementId, weightKg, description, contentCategory, paymentIntentId,
                recipientName, recipientPhone, disclaimerSignedAt, disclaimerSignedIp,
                paymentMethod, photoObjectKeys, commissionChargedVia, promoCode, commissionRate,
                null);
    }
}
