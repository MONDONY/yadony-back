package com.yadony.api.payments.cash.dto;

import com.yadony.api.payments.cash.CommissionLine;
import com.yadony.api.payments.cash.CommissionSplit;
import com.yadony.api.payments.cash.WalletCommissionCollector;

import java.math.BigDecimal;
import java.util.List;

/**
 * Aperçu, avant confirmation, d'où partira la commission d'un colis. Indicatif : les soldes sont lus
 * sans verrou et le bon de réduction éventuel du voyageur n'est pas appliqué (le prélèvement réel peut
 * donc être inférieur, jamais supérieur).
 *
 * <p>{@code lines} est vide tant que {@code covered} est faux ; {@code fundingDetail} dit alors pourquoi
 * (ce que chaque portefeuille règlerait, ce qui manque, les portefeuilles écartés).
 */
public record CommissionAllocationPreviewDto(String bidCurrency,
                                             BigDecimal commission,
                                             boolean covered,
                                             List<Line> lines,
                                             CommissionFundingDetailDto fundingDetail) {

    /**
     * @param debitAmount  montant débité, dans la devise du portefeuille
     * @param coveredInBid part de la commission réglée, dans la devise du colis
     * @param rateOfTheDay taux du jour pour UNE unité de la devise du colis, dans la devise du portefeuille ;
     *                     {@code null} pour le portefeuille de la devise du colis (aucune conversion)
     */
    public record Line(String currency, BigDecimal debitAmount, BigDecimal coveredInBid, BigDecimal rateOfTheDay) {
    }

    public static CommissionAllocationPreviewDto from(CommissionSplit split, WalletCommissionCollector collector) {
        List<Line> lines = split.lines().stream()
                .map((CommissionLine l) -> new Line(l.walletCurrency(), l.debitAmount(), l.coveredInBid(),
                        l.converted() ? collector.rateOfTheDay(split.bidCurrency(), l.walletCurrency()) : null))
                .toList();
        return new CommissionAllocationPreviewDto(split.bidCurrency(), split.commission(), split.covered(), lines,
                CommissionFundingDetailDto.from(split));
    }
}
