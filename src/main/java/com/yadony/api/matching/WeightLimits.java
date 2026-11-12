package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

/**
 * Plafonds de poids par mode de transport, à un seul endroit (même statut qu'{@link ArrivalRules} :
 * règle statique sans dépendance, lisible depuis {@code requests/}).
 *
 * <p>Les colonnes de poids sont en {@code NUMERIC(8,2)} depuis V380 : {@link #MAX_KG} est le
 * maximum technique (route et bateau, « tonnes »). Avion, train, autre et « peu importe »
 * ({@code null}) gardent les plafonds d'avant V380, appliqués ici et non plus en base : 32 kg pour
 * une demande (ancien CHECK {@code chk_pkg_req_weight}), 999,99 kg pour un trajet (ancienne
 * précision {@code NUMERIC(5,2)}, refus désormais en 422 au lieu d'une 500 SQL), 40 kg pour une
 * série. Aucune borne ne dépasse la précision de sa colonne.
 *
 * <p>Miroir côté app : {@code PackageRequestLimits}, {@code TripCapacityLimits}.
 */
public final class WeightLimits {

    /** Maximum technique de toute colonne de poids (NUMERIC(8,2), V380). */
    public static final BigDecimal MAX_KG = new BigDecimal("999999.99");
    /** Demande avion, train, autre et « peu importe » (inchangé depuis V291). */
    public static final BigDecimal PLANE_REQUEST_MAX_KG = new BigDecimal("32");
    /** Capacité d'un trajet avion, train, autre (borne effective d'avant V380). */
    public static final BigDecimal PLANE_TRIP_MAX_KG = new BigDecimal("999.99");
    /** Capacité d'une série avion, train, autre (inchangé). */
    public static final BigDecimal PLANE_RECURRENCE_MAX_KG = new BigDecimal("40");

    private WeightLimits() {}

    /** Voiture, bus et bateau transportent des tonnes ; avion, train, autre et « peu importe » non. */
    public static boolean isHeavyMode(TransportMode mode) {
        return mode == TransportMode.CAR || mode == TransportMode.BUS || mode == TransportMode.BOAT;
    }

    public static BigDecimal maxRequestWeight(TransportMode mode) {
        return isHeavyMode(mode) ? MAX_KG : PLANE_REQUEST_MAX_KG;
    }

    public static BigDecimal maxTripCapacity(TransportMode mode) {
        return isHeavyMode(mode) ? MAX_KG : PLANE_TRIP_MAX_KG;
    }

    public static BigDecimal maxRecurrenceCapacity(TransportMode mode) {
        return isHeavyMode(mode) ? MAX_KG : PLANE_RECURRENCE_MAX_KG;
    }

    /** Poids d'une demande de colis ; {@code null} (brouillon incomplet) n'est pas borné ici. */
    public static void assertRequestWeight(BigDecimal kg, TransportMode mode) {
        BigDecimal max = maxRequestWeight(mode);
        if (kg != null && kg.compareTo(max) > 0) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "weight-too-heavy",
                    "problem.weight-too-heavy", format(max));
        }
    }

    /** Capacité d'un trajet (kilos proposés) ; {@code null} n'est pas borné ici. */
    public static void assertTripCapacity(BigDecimal kg, TransportMode mode) {
        BigDecimal max = maxTripCapacity(mode);
        if (kg != null && kg.compareTo(max) > 0) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "trip-capacity-too-high",
                    "problem.trip-capacity-too-high", format(max));
        }
    }

    /** Plafond sans décimale inutile : « 32 », « 999.99 ». */
    private static String format(BigDecimal max) {
        return max.stripTrailingZeros().toPlainString();
    }
}
