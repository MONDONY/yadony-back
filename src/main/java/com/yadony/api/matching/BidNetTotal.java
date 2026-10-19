package com.yadony.api.matching;

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
 * voyageur n'a jamais tarifés. Sinon : Σ articles de grille figés + poids × prix au kilo du trajet.
 */
public final class BidNetTotal {

    private BidNetTotal() {
    }

    /**
     * @param announcement trajet du colis (prix au kilo), null si introuvable : le kilo ne compte alors pas.
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
        BigDecimal pricePerKg = announcement != null ? announcement.getPricePerKg() : null;
        BigDecimal kgNet = (bid.getWeightKg() != null && pricePerKg != null)
                ? bid.getWeightKg().multiply(pricePerKg)
                : BigDecimal.ZERO;
        return gridNet.add(kgNet).setScale(2, RoundingMode.HALF_UP);
    }
}
