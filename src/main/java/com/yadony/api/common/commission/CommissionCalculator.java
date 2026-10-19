package com.yadony.api.common.commission;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * SOURCE UNIQUE du calcul de la commission par paliers (spec 2026-10-18 §4.2 et §4.3), pure et
 * sans état : la même règle pour la carte, les espèces, le mobile money et les devis.
 *
 * <p>Ordre de calcul, un seul arrondi à la fin :
 * <ol>
 *   <li>commission de grille marginale, bornes converties sans arrondi ;</li>
 *   <li>taux spécial : {@code min(grille, net × taux spécial)} ;</li>
 *   <li>code promo appliqué au taux effectif {@code C / net} (6 décimales) par
 *       {@link com.yadony.api.promo.PromoDiscount#applyTo}, plancher 0 ;</li>
 *   <li>minimum de la devise, sauf si la commission est tombée à 0 (promo qui absorbe tout,
 *       taux spécial à 0) : elle reste alors à 0 ;</li>
 *   <li>bon de parrainage multiplié APRÈS le minimum (1,00 € devient 0,50 €) ;</li>
 *   <li>arrondi HALF_UP à l'unité mineure de la devise.</li>
 * </ol>
 */
public final class CommissionCalculator {

    /** Précision du taux effectif, avant la remise du code promo et dans le devis. */
    private static final int RATE_SCALE = 6;

    private CommissionCalculator() { }

    /** Commission arrondie et indicateur « le minimum a relevé la commission ». */
    private record Commission(BigDecimal amount, boolean minimumApplied) { }

    /**
     * Devis complet pour un net du voyageur. {@code net} est ramené à l'unité mineure de la
     * devise (HALF_UP), comme {@code PriceBreakdown.fromNet}.
     *
     * <p>{@code promoEffective} compare la commission finale avec et sans le code promo, toutes
     * autres réductions égales : faux quand le minimum absorbe tout le gain.
     */
    public static CommissionResult compute(BigDecimal net, SupportedCurrency c, CommissionGrid g,
                                           CommissionReductions r) {
        Objects.requireNonNull(net, "net");
        Objects.requireNonNull(c, "currency");
        Objects.requireNonNull(g, "grid");
        CommissionReductions reductions = r == null ? CommissionReductions.NONE : r;

        BigDecimal n = net.setScale(c.minorUnit(), RoundingMode.HALF_UP);
        if (n.signum() <= 0) {
            throw new IllegalArgumentException("Le net doit être strictement positif");
        }

        Commission commission = commission(n, c, g, reductions);
        boolean promoEffective = reductions.promo() != null
                && commission.amount().compareTo(commission(n, c, g, reductions.withoutPromo()).amount()) < 0;

        return new CommissionResult(n, commission.amount(), n.add(commission.amount()),
                commission.amount().divide(n, RATE_SCALE, RoundingMode.HALF_UP),
                commission.minimumApplied(), promoEffective);
    }

    /**
     * Plus grand net {@code N}, à l'unité mineure de la devise, tel que
     * {@code N + C(N) ≤ gross} : budget TTC d'une demande de colis, brut négocié sur un trajet.
     *
     * <p>Sans code promo, {@code N ↦ N + C(N)} est strictement croissante sur les unités
     * mineures (grille, taux spécial et bon à taux positifs, minimum constant, arrondi monotone) :
     * une recherche dichotomique entre une unité mineure et {@code gross} trouve donc la valeur
     * exacte, sans approximation analytique à corriger d'un pas.
     *
     * <p>Un code promo n'est pas accepté : il peut rendre la fonction décroissante (une promo de
     * 6 points sur la grille 7/6/5 ne laisse aucune commission au-delà de 400 €, alors que 399 €
     * paient le minimum), et la spec n'inverse que la grille, le taux spécial, le bon et le minimum.
     *
     * @throws YadonyBusinessException 422 {@code budget-below-commission-minimum} quand même la
     *         plus petite unité de net coûte plus que {@code gross}, c'est-à-dire quand
     *         {@code gross ≤ C(0+)} (1,00 € avec le minimum par défaut, 0,50 € avec un bon ×0,5).
     */
    public static BigDecimal netForGross(BigDecimal gross, SupportedCurrency c, CommissionGrid g,
                                         CommissionReductions r) {
        Objects.requireNonNull(gross, "gross");
        Objects.requireNonNull(c, "currency");
        Objects.requireNonNull(g, "grid");
        CommissionReductions reductions = r == null ? CommissionReductions.NONE : r;
        if (reductions.promo() != null) {
            throw new IllegalArgumentException("Le calcul inverse ne prend pas de code promo");
        }

        long lo = 1;
        long hi = gross.setScale(c.minorUnit(), RoundingMode.FLOOR).movePointRight(c.minorUnit()).longValueExact();
        if (hi < lo || grossOf(lo, c, g, reductions).compareTo(gross) > 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "budget-below-commission-minimum", "Budget Below Commission Minimum",
                    "Le montant ne couvre pas la commission minimum de Yadony");
        }
        // Invariant : grossOf(lo) ≤ gross ; on cherche le plus grand lo qui le respecte.
        while (lo < hi) {
            long mid = lo + (hi - lo + 1) / 2;
            if (grossOf(mid, c, g, reductions).compareTo(gross) <= 0) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return BigDecimal.valueOf(lo, c.minorUnit());
    }

    private static BigDecimal grossOf(long netMinor, SupportedCurrency c, CommissionGrid g,
                                      CommissionReductions r) {
        BigDecimal n = BigDecimal.valueOf(netMinor, c.minorUnit());
        return n.add(commission(n, c, g, r).amount());
    }

    /** Chaîne de la spec §4.2, étapes 2 à 7, pour un net déjà à l'unité mineure. */
    private static Commission commission(BigDecimal n, SupportedCurrency c, CommissionGrid g,
                                         CommissionReductions r) {
        BigDecimal base = g.gridCommission(n, c);
        if (r.overrideRate() != null) {
            base = base.min(n.multiply(r.overrideRate()));
        }

        BigDecimal afterPromo = base;
        if (r.promo() != null) {
            BigDecimal effectiveRate = base.divide(n, RATE_SCALE, RoundingMode.HALF_UP);
            afterPromo = n.multiply(r.promo().applyTo(effectiveRate));
        }
        if (afterPromo.signum() == 0) {
            // Taux nul après promo ou taux spécial à 0 : ni minimum, ni bon.
            return new Commission(BigDecimal.ZERO.setScale(c.minorUnit()), false);
        }

        BigDecimal minimum = g.minimum(c);
        boolean minimumApplied = afterPromo.compareTo(minimum) < 0;
        BigDecimal amount = afterPromo.max(minimum);
        if (r.voucherFactor() != null) {
            amount = amount.multiply(r.voucherFactor());
        }
        return new Commission(amount.setScale(c.minorUnit(), RoundingMode.HALF_UP), minimumApplied);
    }
}
