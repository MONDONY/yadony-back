package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Modification d'un colis accepté (kilos ou articles de grille ajoutés par l'expéditeur), servie par les
 * routes {@code /bids/{bidId}/amendments}. Montants dans la devise du colis, dates en UTC avec leur décalage
 * ({@code OffsetDateTime}, comme {@link BidSupplementResponse}).
 *
 * <p>SÉCURITÉ (règle métier) : l'expéditeur ne reçoit que le BRUT. {@code netAmount},
 * {@code commissionAmount}, {@code commissionStatus} et le net de chaque article sont nuls pour lui : avec
 * deux des trois montants, il retrouverait le net du voyageur par soustraction.
 *
 * @param status          {@code AUTHORIZATION_PENDING}, {@code PROPOSED}, {@code COMMISSION_PENDING},
 *                        {@code PAYMENT_PENDING}, {@code ACCEPTED}, {@code PAYOUT_PENDING}, {@code PAID_OUT},
 *                        {@code DECLINED}, {@code EXPIRED}, {@code CANCELLED}, {@code REFUND_PENDING}, {@code REFUNDED}.
 * @param rail            {@code STRIPE}, {@code PAWAPAY} ou {@code CASH} : celui choisi par l'expéditeur.
 * @param items           articles de grille ajoutés ; le brut d'une ligne est sa part du brut total, au prorata du net.
 * @param expiresAt       échéance de réponse du voyageur.
 * @param paymentExpiresAt mobile money : échéance du dépôt de l'expéditeur, après l'acceptation.
 * @param closedReason    pourquoi elle a été close sans aboutir, sinon nul.
 * @param blocksHandover  vrai tant qu'elle attend une autorisation, une réponse ou un paiement dans son délai :
 *                        la remise (DEPART) est refusée.
 * @param commissionClientSecret rail espèces, voyageur seulement : client secret de la 3DS de la commission,
 *                        renseigné dans la seule réponse qui la déclenche.
 */
public record BidAmendmentResponse(
        UUID id,
        String status,
        String rail,
        String currency,
        BigDecimal addedWeightKg,
        List<Item> items,
        BigDecimal grossAmount,
        BigDecimal netAmount,
        BigDecimal commissionAmount,
        OffsetDateTime expiresAt,
        OffsetDateTime paymentExpiresAt,
        String closedReason,
        boolean blocksHandover,
        String commissionStatus,
        String commissionClientSecret) {

    /** Article de grille ajouté. {@code netAmount} nul pour l'expéditeur. */
    public record Item(String label, int quantity, BigDecimal grossAmount, BigDecimal netAmount) {
    }

    /** Même vue, avec le client secret de la 3DS de la commission. */
    public BidAmendmentResponse withCommissionClientSecret(String clientSecret) {
        return new BidAmendmentResponse(id, status, rail, currency, addedWeightKg, items, grossAmount, netAmount,
                commissionAmount, expiresAt, paymentExpiresAt, closedReason, blocksHandover, commissionStatus,
                clientSecret);
    }
}
