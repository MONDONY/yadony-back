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

    /**
     * Taille d'une demande de colis gardée en voiture, bus et « peu importe » ({@code mode == null}) ;
     * ignorée (effacée) en avion, bateau, train et autre. Facultative : {@code null} reste {@code null}.
     */
    public static ItemSize normalizeRequestItemSize(ItemSize size, TransportMode mode) {
        return mode == null || mode == TransportMode.CAR || mode == TransportMode.BUS ? size : null;
    }

    /**
     * Taille d'une demande à enregistrer à la modification : absente = taille actuelle conservée ;
     * « NONE » (ou valeur inconnue) = effacée ; sinon la taille demandée. Puis normalisée selon le mode
     * final, donc effacée quand le nouveau mode l'interdit.
     */
    public static ItemSize resolveRequestItemSizeOnUpdate(String requested, ItemSize current, TransportMode mode) {
        ItemSize candidate = requested == null ? current : ItemSize.parseOrNull(requested);
        return normalizeRequestItemSize(candidate, mode);
    }

    /**
     * Poids maximal par colis gardé en voiture, bus et bateau ; ignoré pour tout autre mode. Zéro
     * (sentinelle d'effacement de la requête) vaut « aucune limite » : {@code null}.
     */
    public static BigDecimal normalizeMaxParcelWeight(BigDecimal kg, TransportMode mode) {
        if (kg == null || kg.signum() <= 0 || !WeightLimits.isHeavyMode(mode)) {
            return null;
        }
        return kg.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Taille maximale à enregistrer à la modification (même règle que {@code TripStops.normalizeOnUpdate}) :
     * absente = taille actuelle conservée ; « NONE » = effacée ; sinon la taille demandée. Puis normalisée
     * selon le mode final, donc toujours effacée hors route.
     */
    public static ItemSize resolveMaxItemSizeOnUpdate(String requested, ItemSize current, TransportMode mode) {
        ItemSize candidate = requested == null ? current : ItemSize.parseOrNull(requested);
        return normalizeMaxItemSize(candidate, mode);
    }

    /**
     * Poids maximal à enregistrer à la modification : absent = poids actuel conservé ; 0 = effacé ;
     * sinon le poids demandé. Puis normalisé selon le mode final.
     */
    public static BigDecimal resolveMaxParcelWeightOnUpdate(BigDecimal requested, BigDecimal current,
                                                            TransportMode mode) {
        return normalizeMaxParcelWeight(requested == null ? current : requested, mode);
    }

    /**
     * Taille maximale d'un modèle ou d'une série (enregistrée en texte) : mêmes règles que
     * {@link #resolveMaxItemSizeOnUpdate}. À la création, {@code current} est nul.
     */
    public static String resolveStoredMaxItemSize(String requested, String current, TransportMode mode) {
        ItemSize size = resolveMaxItemSizeOnUpdate(requested, ItemSize.parseOrNull(current), mode);
        return size == null ? null : size.name();
    }

    /**
     * Poids maximal d'un modèle ou d'une série (enregistré en {@code Double}) : mêmes règles que
     * {@link #resolveMaxParcelWeightOnUpdate}. À la création, {@code current} est nul.
     */
    public static Double resolveStoredMaxParcelWeight(Double requested, Double current, TransportMode mode) {
        BigDecimal kg = resolveMaxParcelWeightOnUpdate(toDecimal(requested), toDecimal(current), mode);
        return kg == null ? null : kg.doubleValue();
    }

    private static BigDecimal toDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /** Route : kilo exact (le voyageur donne un nombre de kilos) ; sinon valise de 23 kg. */
    public static CapacityUnit defaultCapacityUnit(TransportMode mode) {
        return mode == TransportMode.CAR || mode == TransportMode.BUS
                ? CapacityUnit.KG_EXACT
                : CapacityUnit.SUITCASE_23KG;
    }

    /** Volume minimal d'un colis : 1 litre. En dessous, l'arrondi à 3 décimales donnerait 0, refusé par les CHECK. */
    public static final BigDecimal MIN_VOLUME_M3 = new BigDecimal("0.001");

    private static final BigDecimal CM3_PER_M3 = new BigDecimal("1000000");

    /**
     * Volume en m³ d'un colis de {@code lengthCm} x {@code widthCm} x {@code heightCm} : produit en cm³ divisé par
     * 1 000 000, arrondi à 3 décimales (HALF_UP), jamais sous {@link #MIN_VOLUME_M3}. Calculé côté serveur, jamais
     * lu du client. Les dimensions sont déjà bornées à 1-400 cm par la validation de la requête.
     */
    public static BigDecimal volumeM3(int lengthCm, int widthCm, int heightCm) {
        BigDecimal cm3 = BigDecimal.valueOf((long) lengthCm * widthCm * heightCm);
        BigDecimal m3 = cm3.divide(CM3_PER_M3, 3, RoundingMode.HALF_UP);
        return m3.compareTo(MIN_VOLUME_M3) < 0 ? MIN_VOLUME_M3 : m3;
    }

    /**
     * Le volume d'un colis tient dans le volume restant d'un trajet au m³ ; un côté inconnu (trajet sans volume,
     * demande sans dimensions) ne bloque rien (spec §5.6). Logique pure : la mise en relation reste fermée aux
     * trajets au m³ jusqu'à la réservation au volume (BB2), qui s'en servira.
     */
    public static boolean fitsVolume(BigDecimal availableVolumeM3, BigDecimal volumeM3) {
        return availableVolumeM3 == null || volumeM3 == null || volumeM3.compareTo(availableVolumeM3) <= 0;
    }

    /** Dimensions d'une demande de colis (cm) et volume calculé (m³) : toutes renseignées ou toutes nulles. */
    public record RequestDimensions(Integer lengthCm, Integer widthCm, Integer heightCm, BigDecimal volumeM3) {
        public static final RequestDimensions NONE = new RequestDimensions(null, null, null, null);

        public boolean isPresent() {
            return volumeM3 != null;
        }
    }

    /**
     * Dimensions d'une demande à enregistrer. Gardées en bateau et en « peu importe » ({@code mode == null}),
     * ignorées (effacées) pour tout autre mode, sans contrôle.
     *
     * <ul>
     *   <li>trois dimensions : volume calculé par {@link #volumeM3} (jamais lu du client) ;</li>
     *   <li>une ou deux seulement : 422 {@code dimensions-incomplete} (toutes ou aucune) ;</li>
     *   <li>aucune : {@code current} conservé quand il existe (modification par une ancienne application,
     *       qui ne les envoie pas) ; sinon 422 {@code boat-dimensions-required} en bateau si
     *       {@code requiredForBoat} (un brouillon peut rester sans dimensions, la publication les exige).</li>
     * </ul>
     * À la création, {@code current} vaut {@link RequestDimensions#NONE}.
     */
    public static RequestDimensions resolveRequestDimensions(Integer lengthCm, Integer widthCm, Integer heightCm,
                                                             RequestDimensions current, TransportMode mode,
                                                             boolean requiredForBoat) {
        if (mode != null && mode != TransportMode.BOAT) {
            return RequestDimensions.NONE;
        }
        int given = (lengthCm == null ? 0 : 1) + (widthCm == null ? 0 : 1) + (heightCm == null ? 0 : 1);
        if (given == 3) {
            return new RequestDimensions(lengthCm, widthCm, heightCm, volumeM3(lengthCm, widthCm, heightCm));
        }
        if (given > 0) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "dimensions-incomplete",
                    "problem.dimensions-incomplete");
        }
        if (current != null && current.isPresent()) {
            return current;
        }
        if (mode == TransportMode.BOAT && requiredForBoat) {
            throw boatDimensionsRequired();
        }
        return RequestDimensions.NONE;
    }

    /** Une demande bateau ne se publie pas sans dimensions : sans volume, aucun trajet au m³ ne peut la prendre. */
    public static void assertBoatDimensionsPresent(TransportMode mode, BigDecimal volumeM3) {
        if (mode == TransportMode.BOAT && volumeM3 == null) {
            throw boatDimensionsRequired();
        }
    }

    private static YadonyBusinessException boatDimensionsRequired() {
        return YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "boat-dimensions-required",
                "problem.boat-dimensions-required");
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
