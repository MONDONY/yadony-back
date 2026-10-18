package com.yadony.api.requests.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Devis transparent pour l'expéditeur au moment d'accepter/payer un thread de
 * négociation : net voyageur, taux et montant de commission Yadony (toujours
 * au taux de base, non affecté par le promo — cf. {@code BidQuoteResponse}),
 * total réellement payé (promo déjà déduit), et statut du code promo.
 *
 * <p>Exception, le devis d'un nouveau budget ({@code /package-requests/quote}) sous grille :
 * {@code commissionEur} y est la commission code promo déduit et {@code totalEur = netEur +
 * commissionEur}, l'expéditeur payant moins que son budget (le net, public, ne dépend pas du code).
 */
public record NegotiationQuoteResponse(
    BigDecimal netEur,
    BigDecimal rate,
    BigDecimal commissionEur,
    BigDecimal totalEur,
    boolean promoApplied,
    String promoLabel,
    /** Sous grille : le minimum de la devise a relevé la commission finale. Toujours faux sans grille. */
    boolean minimumApplied,
    /** Sous grille : commission minimum de la devise du devis ; {@code null} sans grille. */
    BigDecimal minimumAmount,
    /** Grille appliquée (celle du fil, ou celle en vigueur pour un nouveau budget) ; {@code null} sans grille. */
    UUID commissionGridId,
    /**
     * {@code "COMMISSION_MINIMUM"} quand un code promo valide est fourni mais ne change rien
     * (le minimum annule tout le gain) : il n'est alors ni appliqué ni consommé. {@code null} sinon.
     */
    String promoIneffectiveReason
) {}
