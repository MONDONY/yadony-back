package com.yadony.api.admin;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidGridItemEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Calcul du net voyageur d'une demande, partagé par les écrans admin (liste des colis, fiche utilisateur). */
public final class AdminBidAmounts {

    private AdminBidAmounts() {}

    /**
     * Net voyageur d'une demande : l'accord négocié quand il existe, sinon le barème
     * (poids × prix au kilo, plus les articles de la grille), dans la devise du bid.
     */
    public static BigDecimal netOf(BidEntity b, AnnouncementEntity ann, BigDecimal gridNet) {
        if (b.getNegotiatedNetEur() != null) return b.getNegotiatedNetEur();
        BigDecimal kgNet = (b.getWeightKg() != null && ann != null && ann.getPricePerKg() != null)
                ? b.getWeightKg().multiply(ann.getPricePerKg())
                : BigDecimal.ZERO;
        BigDecimal total = kgNet.add(gridNet != null ? gridNet : BigDecimal.ZERO);
        return total.signum() > 0 ? total.setScale(2, RoundingMode.HALF_UP) : null;
    }

    /** Somme des articles de grille par bid. */
    public static Map<UUID, BigDecimal> gridNetByBid(Collection<BidGridItemEntity> items) {
        Map<UUID, BigDecimal> totals = new HashMap<>();
        for (BidGridItemEntity item : items) {
            BigDecimal line = item.getUnitPriceNetSnapshot().multiply(BigDecimal.valueOf(item.getQuantity()));
            totals.merge(item.getBidId(), line, BigDecimal::add);
        }
        return totals;
    }
}
