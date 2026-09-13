package com.yadony.api.matching.dto;

import com.yadony.api.payments.currency.SupportedCurrency;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Feuille « Revenus » du hub Activités : chaque livraison dans la devise de son
 * paiement, regroupée par devise. Aucune conversion ici, c'est tout l'objet.
 */
public record RevenueDetailsDto(
        String period,
        long deliveries,
        List<RevenueGroupDto> groups
) {
    /** Une ligne et sa devise, avant regroupement. */
    public record RevenueLine(String currency, RevenueItemDto item) {}

    private static final Comparator<RevenueItemDto> BY_DATE_THEN_AMOUNT_DESC =
            Comparator.comparing(RevenueItemDto::date, Comparator.reverseOrder())
                    .thenComparing(RevenueItemDto::amount, Comparator.reverseOrder());

    /**
     * Regroupe par code devise (ordre alphabétique stable), trie les lignes par
     * date puis montant décroissants, arrondit lignes et sous-totaux à l'unité
     * mineure de la devise. Le sous-total est arrondi après la somme des
     * montants bruts, comme le total du résumé.
     */
    public static RevenueDetailsDto of(String period, List<RevenueLine> lines) {
        Map<String, List<RevenueItemDto>> byCurrency = new TreeMap<>();
        for (RevenueLine line : lines) {
            byCurrency.computeIfAbsent(line.currency(), c -> new ArrayList<>()).add(line.item());
        }
        List<RevenueGroupDto> groups = new ArrayList<>();
        long deliveries = 0;
        for (Map.Entry<String, List<RevenueItemDto>> entry : byCurrency.entrySet()) {
            int scale = SupportedCurrency.fromCodeOrDefault(entry.getKey()).minorUnit();
            BigDecimal total = entry.getValue().stream()
                    .map(RevenueItemDto::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(scale, RoundingMode.HALF_UP);
            List<RevenueItemDto> items = entry.getValue().stream()
                    .sorted(BY_DATE_THEN_AMOUNT_DESC)
                    .map(item -> item.withAmount(item.amount().setScale(scale, RoundingMode.HALF_UP)))
                    .toList();
            groups.add(new RevenueGroupDto(entry.getKey(), total, items.size(), items));
            deliveries += items.size();
        }
        return new RevenueDetailsDto(period, deliveries, List.copyOf(groups));
    }
}
