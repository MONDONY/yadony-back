package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Limites par colis d'un trajet route ou bateau : taille maximale (voiture, bus) et poids maximal
 * (voiture, bus, bateau), plus l'unité de capacité par défaut selon le mode.
 *
 * <p>Même statut que {@link WeightLimits} : règle statique sans dépendance. Une limite absente
 * ({@code null}) ou un côté inconnu (colis sans poids ou sans taille) ne bloque jamais : un trajet
 * d'avant ces limites, une ancienne application ou une demande sans taille continuent de passer.
 */
public final class ParcelFitRules {

    private ParcelFitRules() {}

    /** Taille maximale gardée en voiture et bus ; ignorée (effacée) pour tout autre mode. */
    public static ItemSize normalizeMaxItemSize(ItemSize size, TransportMode mode) {
        return mode == TransportMode.CAR || mode == TransportMode.BUS ? size : null;
    }

    /** Poids maximal par colis gardé en voiture, bus et bateau ; ignoré pour tout autre mode. */
    public static BigDecimal normalizeMaxParcelWeight(BigDecimal kg, TransportMode mode) {
        if (kg == null || !WeightLimits.isHeavyMode(mode)) {
            return null;
        }
        return kg.setScale(2, RoundingMode.HALF_UP);
    }

    /** Route : kilo exact (le voyageur donne un nombre de kilos) ; sinon valise de 23 kg. */
    public static CapacityUnit defaultCapacityUnit(TransportMode mode) {
        return mode == TransportMode.CAR || mode == TransportMode.BUS
                ? CapacityUnit.KG_EXACT
                : CapacityUnit.SUITCASE_23KG;
    }

    /** Refuse un colis plus lourd ou plus grand que ce que le trajet accepte (422). */
    public static void assertFits(AnnouncementEntity trip, BigDecimal weightKg, ItemSize itemSize) {
        BigDecimal maxKg = trip.getMaxParcelWeightKg();
        if (tooHeavy(maxKg, weightKg)) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "parcel-too-heavy",
                    "problem.parcel-too-heavy", maxKg.stripTrailingZeros().toPlainString());
        }
        ItemSize maxSize = trip.getMaxItemSize();
        if (tooBig(maxSize, itemSize)) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "parcel-too-big",
                    "problem.parcel-too-big", maxSize.name());
        }
    }

    /** Même règle qu'{@link #assertFits}, sans exception (recherche et mise en relation). */
    public static boolean fits(AnnouncementEntity trip, BigDecimal weightKg, ItemSize itemSize) {
        return !tooHeavy(trip.getMaxParcelWeightKg(), weightKg) && !tooBig(trip.getMaxItemSize(), itemSize);
    }

    private static boolean tooHeavy(BigDecimal maxKg, BigDecimal weightKg) {
        return maxKg != null && weightKg != null && weightKg.compareTo(maxKg) > 0;
    }

    private static boolean tooBig(ItemSize maxSize, ItemSize itemSize) {
        return maxSize != null && itemSize != null && !itemSize.fitsWithin(maxSize);
    }
}
