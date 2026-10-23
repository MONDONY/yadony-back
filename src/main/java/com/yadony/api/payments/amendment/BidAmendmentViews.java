package com.yadony.api.payments.amendment;

import com.yadony.api.matching.dto.BidAmendmentResponse;
import com.yadony.api.payments.currency.SupportedCurrency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/** Conversion d'une modification en {@link BidAmendmentResponse}, règle de visibilité comprise. */
public final class BidAmendmentViews {

    private BidAmendmentViews() {
    }

    /**
     * @param items     articles de la modification ({@link BidAmendmentItemRepository#findByAmendmentId}).
     * @param forSender vrai pour l'expéditeur du colis : ni net (total ou par article), ni commission, ni
     *                  statut de commission. Il retrouverait sinon le net du voyageur par soustraction.
     */
    public static BidAmendmentResponse of(BidAmendmentEntity a, List<BidAmendmentItemEntity> items, boolean forSender,
                                          BidAmendmentWindow window) {
        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(a.getCurrency());
        List<BidAmendmentResponse.Item> lines = items.stream().map(item -> {
            BigDecimal net = item.getUnitPriceNetSnapshot().multiply(BigDecimal.valueOf(item.getQuantity()))
                    .setScale(currency.minorUnit(), RoundingMode.HALF_UP);
            return new BidAmendmentResponse.Item(item.getLabelSnapshot(), item.getQuantity(),
                    lineGross(net, a, currency), forSender ? null : net);
        }).toList();
        return new BidAmendmentResponse(
                a.getId(),
                a.getStatus().name(),
                a.getRail().name(),
                a.getCurrency(),
                a.getAddedWeightKg(),
                lines,
                a.getGrossAmount(),
                forSender ? null : a.getNetAmount(),
                forSender ? null : a.getCommissionAmount(),
                a.getExpiresAt(),
                a.getPaymentExpiresAt(),
                a.getClosedReason() == null ? null : a.getClosedReason().name(),
                blocksHandover(a, window.now(), window),
                forSender || a.getCommissionStatus() == null ? null : a.getCommissionStatus().name(),
                null);
    }

    /**
     * La modification retient-elle la remise ? Seulement ouverte et avant la fin de son étape en cours
     * ({@link BidAmendmentWindow#openUntil}) : échue, personne ne peut plus y répondre, elle ne doit pas
     * bloquer le colis en attendant le filet.
     */
    static boolean blocksHandover(BidAmendmentEntity a, LocalDateTime now, BidAmendmentWindow window) {
        LocalDateTime until = window.openUntil(a);
        return until != null && until.isAfter(now);
    }

    /**
     * Brut d'un article : sa part du brut total au prorata de son net. La commission différentielle se calcule
     * sur le total, pas par ligne ; cette part n'est qu'un affichage.
     */
    private static BigDecimal lineGross(BigDecimal lineNet, BidAmendmentEntity a, SupportedCurrency currency) {
        if (a.getNetAmount() == null || a.getNetAmount().signum() <= 0) {
            return lineNet;
        }
        return lineNet.multiply(a.getGrossAmount())
                .divide(a.getNetAmount(), currency.minorUnit(), RoundingMode.HALF_UP);
    }
}
