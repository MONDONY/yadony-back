package com.yadony.api.admin.dto;

import com.yadony.api.common.commission.CommissionGridWarning;

import java.math.BigDecimal;
import java.util.List;

/**
 * Résultat de la simulation, une ligne par net. Montants dans la devise demandée, arrondis à son
 * unité mineure, sauf {@code marginExVat} à 4 décimales. Chiffres indicatifs pour le propriétaire,
 * jamais utilisés pour fixer un prix.
 *
 * @param warnings                avertissements que lèverait la programmation du brouillon (vide pour {@code gridId}).
 * @param connectActiveAccountMonthly coût mensuel Stripe Connect d'un compte actif, affiché à part.
 */
public record CommissionSimulationResponse(List<Row> rows, List<CommissionGridWarning> warnings,
                                           BigDecimal connectActiveAccountMonthly) {

    public enum Rail { CARD_STANDARD, CARD_PREMIUM, CARD_INTERNATIONAL, MOBILE_MONEY_WORST, MOBILE_MONEY_BEST, CASH }

    /** Commission de la grille en vigueur (ou, sans grille, du taux plat actuel). */
    public record Current(BigDecimal commission, BigDecimal gross) { }

    public record Draft(BigDecimal commission, BigDecimal gross, boolean minimumApplied) { }

    /** Coût du prestataire et marge hors taxes de Yadony sur la commission du brouillon. */
    public record RailCost(Rail rail, BigDecimal providerCost, BigDecimal marginExVat) { }

    public record Row(BigDecimal net, Current current, Draft draft, List<RailCost> rails) { }
}
