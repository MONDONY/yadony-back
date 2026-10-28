package com.yadony.api.payments.cash.dto;

import com.yadony.api.payments.cash.CommissionSplit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Détail d'un « solde insuffisant » toutes devises confondues : ce que chaque portefeuille utilisable
 * règlerait dans la devise du colis, le total, ce qui manque encore, et les portefeuilles écartés avec
 * leur motif ({@code frozen}, {@code rate-stale}, {@code rate-missing}). Additif : l'app qui ne le lit
 * pas continue d'utiliser {@code availableBalance}, {@code requiredCommission} et {@code breakdown}.
 *
 * <p>Un « solde insuffisant » signifie désormais que la SOMME de tous les portefeuilles utilisables
 * ne couvre pas la commission : aucun autre portefeuille ne peut régler le reste seul.
 */
public record CommissionFundingDetailDto(String bidCurrency,
                                         BigDecimal commission,
                                         BigDecimal availableInBid,
                                         BigDecimal shortfallInBid,
                                         List<CommissionSplit.WalletShare> wallets,
                                         Map<String, String> excluded) {

    public static CommissionFundingDetailDto from(CommissionSplit s) {
        return new CommissionFundingDetailDto(s.bidCurrency(), s.commission(), s.availableInBid(),
                s.shortfallInBid(), s.wallets(), s.excluded());
    }
}
