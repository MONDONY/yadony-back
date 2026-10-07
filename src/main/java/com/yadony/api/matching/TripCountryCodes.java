package com.yadony.api.matching;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Codes pays ISO-2 d'un trajet (FLUTTER-EH).
 *
 * <p>Le client envoie le code capté à la sélection de la ville, mais plusieurs chemins le
 * perdaient : modèle de trajet appliqué, modification d'un trajet (le détail ne servait pas
 * le code), trajet dédié né d'une demande de colis, trajet récurrent. Ces règles gardent
 * le code fourni quand il est exploitable et, à défaut, le déduisent de la ville via le
 * référentiel {@code cities} — ville la plus peuplée portant ce nom, règle déjà retenue
 * par {@code CityRepository#findTopByNamesIgnoreCase} et qui concorde avec les 98 trajets
 * de staging dont le code venait du client.
 */
public final class TripCountryCodes {

    private TripCountryCodes() {
    }

    /**
     * Code à enregistrer à la création : le code fourni (normalisé), sinon celui de la ville.
     *
     * @param provided code envoyé par le client, éventuellement nul ou vide
     * @param city     nom de ville du trajet
     * @param lookup   recherche du code pays par nom de ville dans le référentiel
     * @return code ISO-2 en majuscules, ou {@code null} si la ville est inconnue
     */
    public static String resolve(String provided, String city,
                                 Function<String, Optional<String>> lookup) {
        String normalized = normalize(provided);
        if (normalized != null) {
            return normalized;
        }
        return fromCity(city, lookup);
    }

    /**
     * Code à enregistrer à la modification. Un code fourni prime ; s'il manque, le code
     * existant est conservé tant que la ville ne change pas (l'édition ne le renvoyait pas
     * et l'effaçait), sinon il est déduit de la nouvelle ville.
     */
    public static String resolveOnUpdate(String provided, String newCity,
                                         String currentCity, String currentCode,
                                         Function<String, Optional<String>> lookup) {
        String normalized = normalize(provided);
        if (normalized != null) {
            return normalized;
        }
        if (sameCity(newCity, currentCity)) {
            String kept = normalize(currentCode);
            if (kept != null) {
                return kept;
            }
        }
        return fromCity(newCity, lookup);
    }

    static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        if (trimmed.length() != 2) {
            return null;
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private static String fromCity(String city, Function<String, Optional<String>> lookup) {
        if (city == null || city.isBlank()) {
            return null;
        }
        Optional<String> found = lookup.apply(city.trim());
        return found == null ? null : found.map(TripCountryCodes::normalize).orElse(null);
    }

    private static boolean sameCity(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }
}
