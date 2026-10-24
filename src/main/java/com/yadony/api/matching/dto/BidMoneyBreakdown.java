package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Argent d'un colis modifié, calculé par le serveur (l'app ne recalcule jamais) : une ligne par transaction (colis
 * initial, supplément payé, modifications acquises ou en attente), le total, puis le total par destination de
 * l'argent. Servie seulement quand le colis a une modification acquise ou ouverte ; sinon nulle, et l'app garde ses
 * cartes actuelles.
 *
 * <p>Expéditeur : des bruts (ce qu'il paie), jamais de net ni de commission. Voyageur : des nets (ce qu'il gagne),
 * sauf {@code cashAmount}, le brut qu'il encaisse en main propre, dont {@code cashCommissionPaid} lui a déjà été
 * prélevé.
 *
 * @param lines              transactions, dans l'ordre : colis initial, supplément, modifications.
 * @param total              somme des lignes acquises ({@code SECURED}) ; une ligne en attente n'y entre pas.
 * @param onlineAmount       lignes acquises carte ou mobile money : payé en ligne et gardé jusqu'à la livraison
 *                           (expéditeur), versé sur le compte après la livraison (voyageur).
 * @param cashAmount         brut des lignes acquises en espèces : à remettre (expéditeur), à encaisser (voyageur).
 * @param cashCommissionPaid voyageur seulement (nul pour l'expéditeur) : commission Yadony des lignes en espèces,
 *                           déjà réglée sur son solde ou sa carte.
 */
public record BidMoneyBreakdown(
        List<Line> lines,
        BigDecimal total,
        BigDecimal onlineAmount,
        BigDecimal cashAmount,
        BigDecimal cashCommissionPaid) {

    public static final String KIND_PRINCIPAL = "PRINCIPAL";
    public static final String KIND_AMENDMENT = "AMENDMENT";
    public static final String KIND_SUPPLEMENT = "SUPPLEMENT";

    public static final String METHOD_STRIPE = "STRIPE";
    public static final String METHOD_MOBILE_MONEY = "MOBILE_MONEY";
    public static final String METHOD_CASH = "CASH";

    public static final String STATUS_SECURED = "SECURED";
    public static final String STATUS_PENDING = "PENDING";

    /**
     * @param kind   {@code PRINCIPAL}, {@code AMENDMENT} ou {@code SUPPLEMENT}.
     * @param refId  le colis (principal), le supplément ou la modification.
     * @param method {@code STRIPE}, {@code MOBILE_MONEY} ou {@code CASH}.
     * @param amount brut pour l'expéditeur, net pour le voyageur.
     * @param status {@code SECURED} (acquis) ou {@code PENDING} (modification en attente).
     */
    public record Line(String kind, UUID refId, String method, BigDecimal amount, String status) {
    }

    /**
     * Une transaction du colis, avec tous ses montants : entrée du calcul, jamais servie telle quelle (elle porte le
     * net et la commission, que l'expéditeur ne doit pas voir).
     *
     * @param commission commission Yadony prélevée au voyageur pour une ligne en espèces, sinon ignorée.
     * @param secured    acquise (comptée dans les totaux) ; sinon en attente.
     */
    public record Entry(String kind, UUID refId, String method, BigDecimal net, BigDecimal gross,
                        BigDecimal commission, boolean secured) {
    }

    /**
     * Ventilation vue par l'expéditeur ({@code forSender}) ou par le voyageur, ou null sans aucune modification (acquise
     * ou en attente) parmi les transactions : l'app garde alors ses cartes actuelles, supplément compris.
     *
     * @param scale nombre de décimales de la devise du colis.
     */
    public static BidMoneyBreakdown of(boolean forSender, int scale, List<Entry> entries) {
        if (entries.stream().noneMatch(e -> KIND_AMENDMENT.equals(e.kind()))) {
            return null;
        }
        BigDecimal total = zero(scale);
        BigDecimal online = zero(scale);
        BigDecimal cash = zero(scale);
        BigDecimal commission = zero(scale);
        List<Line> lines = new java.util.ArrayList<>(entries.size());
        for (Entry e : entries) {
            BigDecimal amount = scaled(forSender ? e.gross() : e.net(), scale);
            lines.add(new Line(e.kind(), e.refId(), e.method(), amount,
                    e.secured() ? STATUS_SECURED : STATUS_PENDING));
            if (!e.secured() || amount == null) {
                continue;
            }
            total = total.add(amount);
            if (METHOD_CASH.equals(e.method())) {
                cash = cash.add(orZero(scaled(e.gross(), scale), scale));
                commission = commission.add(orZero(scaled(e.commission(), scale), scale));
            } else {
                online = online.add(amount);
            }
        }
        return new BidMoneyBreakdown(List.copyOf(lines), total, online, cash, forSender ? null : commission);
    }

    /**
     * Moyen d'une ligne à partir d'un moyen de paiement du colis ou d'un rail : carte {@code STRIPE} (et par défaut),
     * {@code CASH}, et tout le mobile money (rail {@code PAWAPAY}, moyens hérités {@code WAVE}, {@code ORANGE_MONEY})
     * en {@code MOBILE_MONEY}.
     */
    public static String methodOf(String paymentMethodOrRail) {
        if (paymentMethodOrRail == null) {
            return METHOD_STRIPE;
        }
        return switch (paymentMethodOrRail) {
            case "CASH" -> METHOD_CASH;
            case "PAWAPAY", "MOBILE_MONEY", "WAVE", "ORANGE_MONEY" -> METHOD_MOBILE_MONEY;
            default -> METHOD_STRIPE;
        };
    }

    private static BigDecimal zero(int scale) {
        return BigDecimal.ZERO.setScale(scale);
    }

    private static BigDecimal orZero(BigDecimal value, int scale) {
        return value == null ? zero(scale) : value;
    }

    private static BigDecimal scaled(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }
}
