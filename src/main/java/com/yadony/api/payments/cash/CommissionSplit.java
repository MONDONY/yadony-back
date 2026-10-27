package com.yadony.api.payments.cash;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Répartition d'une commission espèces entre les portefeuilles du voyageur : celui de la devise du
 * colis d'abord (sans conversion), puis les autres, du plus gros au plus petit en valeur, convertis
 * au taux du jour. Calculée par {@link WalletCommissionCollector#plan}, exécutée par ses méthodes
 * {@code execute*}.
 *
 * <p>{@code lines} est la répartition à débiter, une ligne par portefeuille ; elle est vide tant que
 * {@code covered} est faux. {@code covered} est vrai quand les portefeuilles réunis couvrent la
 * totalité : c'est la seule situation où un débit a lieu (règle « tout ou rien »).
 *
 * <p>Les champs historiques (de {@code fromBidWallet} à {@code commissionInActive}) décrivent la
 * lecture « devise du colis / devise active » que l'app actuelle affiche ; ils restent renseignés
 * pour ne rien casser. {@code commissionInActive} est la commission totale exprimée dans la devise
 * active, destinée à l'affichage ({@code requiredCommission}) : elle ne pilote aucun débit.
 *
 * <p>{@code availableInBid} est ce que tous les portefeuilles utilisables règleraient ensemble,
 * dans la devise du colis (arrondi vers le bas). {@code wallets} détaille chaque portefeuille
 * utilisable, {@code excluded} ceux écartés avec leur motif ({@code frozen}, {@code rate-stale},
 * {@code rate-missing}).
 */
public record CommissionSplit(String bidCurrency,
                              BigDecimal commission,
                              BigDecimal fromBidWallet,
                              BigDecimal remainingBid,
                              String activeCurrency,
                              BigDecimal remainingActive,
                              BigDecimal appliedRate,
                              BigDecimal bidWalletBalance,
                              BigDecimal activeBalance,
                              boolean covered,
                              BigDecimal commissionInActive,
                              List<CommissionLine> lines,
                              BigDecimal availableInBid,
                              List<WalletShare> wallets,
                              Map<String, String> excluded) {

    /** Solde d'un portefeuille utilisable et ce qu'il règlerait dans la devise du colis (arrondi bas). */
    public record WalletShare(String currency, BigDecimal balance, BigDecimal valueInBid) {
    }

    /**
     * Forme historique à deux portefeuilles (devise du colis, devise active) : la répartition est
     * dérivée des champs, sans détail par portefeuille. Conservée pour les appelants et les tests
     * qui décrivent une répartition à la main.
     */
    public CommissionSplit(String bidCurrency, BigDecimal commission, BigDecimal fromBidWallet,
                           BigDecimal remainingBid, String activeCurrency, BigDecimal remainingActive,
                           BigDecimal appliedRate, BigDecimal bidWalletBalance, BigDecimal activeBalance,
                           boolean covered, BigDecimal commissionInActive) {
        this(bidCurrency, commission, fromBidWallet, remainingBid, activeCurrency, remainingActive, appliedRate,
                bidWalletBalance, activeBalance, covered, commissionInActive,
                legacyLines(bidCurrency, fromBidWallet, remainingBid, activeCurrency, remainingActive, appliedRate),
                covered ? commission : bidWalletBalance, List.of(), Map.of());
    }

    private static List<CommissionLine> legacyLines(String bidCurrency, BigDecimal fromBidWallet,
                                                    BigDecimal remainingBid, String activeCurrency,
                                                    BigDecimal remainingActive, BigDecimal appliedRate) {
        List<CommissionLine> lines = new ArrayList<>();
        if (fromBidWallet.signum() > 0) {
            lines.add(new CommissionLine(bidCurrency, fromBidWallet, fromBidWallet, null));
        }
        if (remainingBid.signum() > 0 && remainingActive.signum() > 0 && !bidCurrency.equals(activeCurrency)) {
            lines.add(new CommissionLine(activeCurrency, remainingActive, remainingBid, appliedRate));
        }
        return List.copyOf(lines);
    }

    public CommissionSplit {
        lines = lines == null ? List.of() : List.copyOf(lines);
        wallets = wallets == null ? List.of() : List.copyOf(wallets);
        excluded = excluded == null ? Map.of() : Map.copyOf(excluded);
    }

    public boolean sameCurrency() {
        return bidCurrency.equals(activeCurrency);
    }

    /** Ce qui manque encore, dans la devise du colis (zéro quand la commission est couverte). */
    public BigDecimal shortfallInBid() {
        BigDecimal missing = commission.subtract(availableInBid == null ? BigDecimal.ZERO : availableInBid);
        return missing.max(BigDecimal.ZERO);
    }
}
