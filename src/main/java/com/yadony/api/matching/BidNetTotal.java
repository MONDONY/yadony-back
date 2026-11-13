package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Net total d'un colis, dans la devise du trajet : SOURCE UNIQUE de l'affichage
 * ({@link BidService#toResponse}) et du prix d'un supplément (le supplément se tarife sur le net
 * déjà dû, pour ne payer que la différence de commission).
 *
 * <p>Bid issu d'une négociation : l'accord figé EST le total, on ne le recompose pas. Le
 * recomposer double-compterait les articles de grille (le prix négocié les couvre déjà, et le
 * prix/kg vient d'être réécrit depuis ce même prix) et ignorerait les articles hors grille, que le
 * voyageur n'a jamais tarifés. Sinon : Σ articles de grille figés + part au barème du trajet
 * ({@link #scheduleNet} : poids × prix au kilo, ou volume × prix au m³ pour un bateau vendu au m³).
 */
public final class BidNetTotal {

    /** Plafond technique d'un montant (NUMERIC(10,2) des colonnes d'argent et du prix au m³). */
    static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

    private BidNetTotal() {
    }

    /**
     * @param announcement trajet du colis (prix au kilo ou au m³), null si introuvable : la part au barème ne
     *                     compte alors pas.
     * @param gridItems    articles de grille figés sur le colis.
     * @return net arrondi au centime (HALF_UP).
     */
    public static BigDecimal compute(BidEntity bid, AnnouncementEntity announcement, List<BidGridItemEntity> gridItems) {
        if (bid.getNegotiatedNetEur() != null) {
            return bid.getNegotiatedNetEur().setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal gridNet = gridItems.stream()
                .map(i -> i.getUnitPriceNetSnapshot().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return gridNet.add(scheduleNet(bid, announcement)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Part au barème du trajet d'un colis NON négocié, la même pour l'affichage et pour chaque rail de paiement
     * (carte, espèces, mobile money) : la même mécanique de frais s'applique ensuite au net obtenu.
     *
     * <ul>
     *   <li>Trajet vendu au m³ : {@link #volumeNet} du volume du colis (zéro sans volume). Le poids d'un tel
     *       colis n'est jamais tarifé (le trajet n'a pas de prix au kilo), il ne sert qu'au poids max par colis.</li>
     *   <li>Sinon : poids × prix au kilo, sans arrondi (l'appelant arrondit la somme avec la grille, comme
     *       avant) ; zéro sans poids ou sans prix au kilo.</li>
     * </ul>
     */
    public static BigDecimal scheduleNet(BidEntity bid, AnnouncementEntity announcement) {
        if (announcement == null) {
            return BigDecimal.ZERO;
        }
        if (announcement.isPricedPerM3()) {
            return bid.getVolumeM3() != null ? volumeNet(announcement, bid.getVolumeM3()) : BigDecimal.ZERO;
        }
        BigDecimal pricePerKg = announcement.getPricePerKg();
        return bid.getWeightKg() != null && pricePerKg != null
                ? bid.getWeightKg().multiply(pricePerKg)
                : BigDecimal.ZERO;
    }

    /**
     * Net d'un volume sur un trajet vendu au m³ : {@code volumeM3 × pricePerM3}, arrondi à l'unité mineure de la
     * devise du trajet (HALF_UP : centime en euro, franc entier en F CFA). Calcul pur, jamais d'exception : il sert
     * aussi à l'affichage, qui ne doit pas échouer. La borne se contrôle à la réservation ({@link #bookableVolumeNet}).
     */
    public static BigDecimal volumeNet(AnnouncementEntity trip, BigDecimal volumeM3) {
        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(trip.getCurrency());
        return volumeM3.multiply(trip.getPricePerM3()).setScale(currency.minorUnit(), RoundingMode.HALF_UP);
    }

    /**
     * {@link #volumeNet} contrôlé à la réservation (devis, offre, paiement carte, proposition de prix), avant toute
     * écriture. Aucun plafond métier pour un bateau (décision du propriétaire), seulement la borne technique des
     * colonnes d'argent : au-delà de 99 999 999,99, 422 {@code volume-price-too-high} au lieu d'une erreur SQL au
     * paiement.
     */
    public static BigDecimal bookableVolumeNet(AnnouncementEntity trip, BigDecimal volumeM3) {
        BigDecimal net = volumeNet(trip, volumeM3);
        assertWithinTechnicalBound(net);
        return net;
    }

    /** Montant dérivé d'un volume (net d'un colis, prix au m³ d'un trajet dédié) borné à NUMERIC(10,2), sinon 422. */
    public static void assertWithinTechnicalBound(BigDecimal amount) {
        if (amount != null && amount.compareTo(MAX_AMOUNT) > 0) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "volume-price-too-high",
                    "problem.volume-price-too-high");
        }
    }
}
