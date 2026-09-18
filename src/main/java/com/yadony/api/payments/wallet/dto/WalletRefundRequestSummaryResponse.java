package com.yadony.api.payments.wallet.dto;

import com.yadony.api.payments.wallet.WalletRefundChannel;
import com.yadony.api.payments.wallet.WalletRefundItemStatus;
import com.yadony.api.payments.wallet.WalletRefundRequestEntity;
import com.yadony.api.payments.wallet.WalletRefundRequestItemEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Demande de remboursement. Champs additifs (un ancien client les ignore) : {@code feeAmount}
 * (somme des frais de ses items), {@code netAmount} (ce qui repart vers l'utilisateur),
 * {@code rail} ({@code STRIPE}, {@code PAWAPAY} ou {@code MANUAL}, dérivé du canal) et
 * {@code destinationMasked} (numéro masqué du dépôt mobile money d'origine, {@code null} hors
 * pawaPay ; jamais le numéro en clair).
 */
public record WalletRefundRequestSummaryResponse(
        UUID id,
        String currency,
        BigDecimal amount,
        String channel,
        String status,
        LocalDateTime requestedAt,
        LocalDateTime resolvedAt,
        BigDecimal feeAmount,
        BigDecimal netAmount,
        String rail,
        String destinationMasked
) {
    public static final String RAIL_STRIPE = "STRIPE";
    public static final String RAIL_PAWAPAY = "PAWAPAY";
    public static final String RAIL_MANUAL = "MANUAL";

    /**
     * Sans item (ancien ticket manuel), frais nuls et net égal au montant de la demande ; sinon
     * sommes sur les items : {@code fee = Σ feeAmount}, {@code net = Σ (amount - feeAmount)}.
     *
     * <p>Les items FAILED sont exclus des deux sommes : leur montant ne part pas vers
     * l'utilisateur (une demande partiellement échouée afficherait un net trop élevé). On ne
     * filtre pas sur REFUNDED seul, sinon une demande encore en cours afficherait un net nul.
     * Une demande dont TOUS les items ont échoué donne bien frais 0 et net 0 (rien n'est parti,
     * le wallet n'a pas été débité) — à ne pas confondre avec une demande sans aucun item
     * (ancien ticket manuel), seul cas qui retombe sur le montant demandé.
     */
    public static WalletRefundRequestSummaryResponse from(WalletRefundRequestEntity entity,
                                                          List<WalletRefundRequestItemEntity> items,
                                                          String destinationMasked) {
        BigDecimal fee = BigDecimal.ZERO;
        BigDecimal net = entity.getAmount();
        if (items != null && !items.isEmpty()) {
            List<WalletRefundRequestItemEntity> counted = items.stream()
                    .filter(i -> i.getStatus() != WalletRefundItemStatus.FAILED)
                    .toList();
            fee = counted.stream().map(i -> i.getFeeAmount() == null ? BigDecimal.ZERO : i.getFeeAmount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            net = counted.stream().map(WalletRefundRequestItemEntity::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .subtract(fee);
        }
        return new WalletRefundRequestSummaryResponse(
                entity.getId(), entity.getCurrency(), entity.getAmount(),
                entity.getChannel().name(), entity.getStatus().name(),
                entity.getRequestedAt(), entity.getResolvedAt(),
                fee, net, railOf(entity.getChannel()), destinationMasked);
    }

    private static String railOf(WalletRefundChannel channel) {
        return switch (channel) {
            case AUTOMATIC_STRIPE -> RAIL_STRIPE;
            case AUTOMATIC_PAWAPAY -> RAIL_PAWAPAY;
            case MANUAL_ADMIN -> RAIL_MANUAL;
        };
    }
}
