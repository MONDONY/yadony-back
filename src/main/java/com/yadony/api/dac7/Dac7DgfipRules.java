package com.yadony.api.dac7;

import java.util.Map;
import java.util.Set;

/**
 * Règles de la DGFiP pour le fichier de collecte, lues dans le cahier des charges DPI-DAC7 revenus 2026 (v1.7) :
 * <ul>
 *   <li>annexe 6.1 : pour les opérations de 2026, seuls les 27 États membres de l'Union donnent lieu à transmission
 *       (aucun État partenaire : un résident de Côte d'Ivoire, du Sénégal, du Mali ou du Cameroun n'est pas transmis, le
 *       contrôle de second niveau n° 8 rejette l'enregistrement) ;</li>
 *   <li>annexe 6.4 : un résident de Guadeloupe, Guyane, Martinique, La Réunion ou Mayotte est déclaré {@code FR}
 *       (Saint-Martin, région ultrapériphérique, aussi : non listé, par prudence) ; Åland est déclaré {@code FI}.</li>
 * </ul>
 * Seule classe à mettre à jour quand la DGFiP publie le cahier des charges d'une autre année.
 */
public final class Dac7DgfipRules {

    private static final Map<String, String> TERRITORY_CODES = Map.of(
            "GP", "FR", "GF", "FR", "MQ", "FR", "RE", "FR", "YT", "FR", "MF", "FR", "AX", "FI");

    private Dac7DgfipRules() {
    }

    /** États dont les résidents sont transmis (seule la liste 2026 a été lue ; reprise pour les autres années). */
    public static Set<String> transmissionStates(int year) {
        return Dac7ResidencyPolicy.EU_MEMBER_STATES;
    }

    /** Code de résidence attendu par la DGFiP ({@code ResCountryCode}). */
    public static String resCountryCode(String residenceCountry) {
        return residenceCountry == null ? null : TERRITORY_CODES.getOrDefault(residenceCountry, residenceCountry);
    }

    public static boolean transmitted(int year, String residenceCountry) {
        String code = resCountryCode(residenceCountry);
        return code != null && transmissionStates(year).contains(code);
    }
}
