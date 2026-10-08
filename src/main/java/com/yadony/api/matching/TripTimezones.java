package com.yadony.api.matching;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Fuseau horaire IANA d'un trajet, déduit de sa ville de départ.
 *
 * <p>La date et l'heure de départ sont saisies en heure locale de la ville de départ.
 * Le fuseau valait pourtant toujours « Europe/Paris » (défaut d'entité, jamais réécrit) :
 * l'instant de départ {@code departureAt}, la bascule « en cours », l'échéance de remise
 * et le verrou d'annulation tombaient avec le décalage de Paris (Abidjan 2 h trop tôt,
 * Cotonou 1 h, Toronto 6 h trop tôt…).
 *
 * <p>Ordre de déduction :
 * <ol>
 *   <li>fuseau GeoNames de la ville la plus peuplée de ce nom (référentiel {@code cities}),
 *       celle du pays du trajet en priorité ;</li>
 *   <li>à défaut, fuseau principal du pays (celui de sa ville la plus peuplée) ;</li>
 *   <li>à défaut, {@link #DEFAULT_ZONE}.</li>
 * </ol>
 * Un fuseau inconnu de la JVM est écarté comme s'il manquait.
 */
public final class TripTimezones {

    /** Repli historique : le fuseau de toutes les annonces avant V300. */
    public static final String DEFAULT_ZONE = "Europe/Paris";

    private TripTimezones() {
    }

    /**
     * Sources de déduction, branchées sur le référentiel des villes.
     *
     * @param byCity    fuseau de la ville (nom, code pays ou chaîne vide)
     * @param byCountry fuseau principal d'un pays (code ISO-2)
     */
    public record Lookup(BiFunction<String, String, Optional<String>> byCity,
                         Function<String, Optional<String>> byCountry) {

        /** Branche les requêtes de {@link AnnouncementRepository}. */
        public static Lookup of(AnnouncementRepository repository) {
            return new Lookup(repository::findTimezoneByCityName,
                    repository::findMainTimezoneByCountryCode);
        }
    }

    /**
     * Fuseau à enregistrer à la création d'un trajet.
     *
     * @param departureCity        ville de départ saisie
     * @param departureCountryCode code pays ISO-2 de départ déjà résolu, éventuellement nul
     * @param lookup               sources de déduction
     * @return identifiant IANA valide, jamais nul
     */
    public static String resolve(String departureCity, String departureCountryCode, Lookup lookup) {
        String country = normalizeCountry(departureCountryCode);
        if (departureCity != null && !departureCity.isBlank()) {
            String fromCity = valid(lookup.byCity().apply(departureCity.trim(), country == null ? "" : country));
            if (fromCity != null) {
                return fromCity;
            }
        }
        if (country != null) {
            String fromCountry = valid(lookup.byCountry().apply(country));
            if (fromCountry != null) {
                return fromCountry;
            }
        }
        return DEFAULT_ZONE;
    }

    /**
     * Fuseau à enregistrer à la modification. Inchangé tant que la ville et le pays de
     * départ ne changent pas (et que le fuseau en place est valide) ; sinon déduit à nouveau.
     */
    public static String resolveOnUpdate(String newCity, String newCountryCode,
                                         String currentCity, String currentCountryCode,
                                         String currentZone, Lookup lookup) {
        boolean sameCity = newCity != null && currentCity != null
                && newCity.trim().equalsIgnoreCase(currentCity.trim());
        boolean sameCountry = java.util.Objects.equals(
                normalizeCountry(newCountryCode), normalizeCountry(currentCountryCode));
        if (sameCity && sameCountry) {
            String kept = valid(Optional.ofNullable(currentZone));
            if (kept != null) {
                return kept;
            }
        }
        return resolve(newCity, newCountryCode, lookup);
    }

    /** Fuseau utilisable pour un calcul : celui du trajet s'il est valide, sinon le repli. */
    public static ZoneId zoneOf(String zone) {
        String kept = valid(Optional.ofNullable(zone));
        return ZoneId.of(kept == null ? DEFAULT_ZONE : kept);
    }

    private static String valid(Optional<String> zone) {
        if (zone == null || zone.isEmpty()) {
            return null;
        }
        String candidate = zone.get().trim();
        if (candidate.isEmpty()) {
            return null;
        }
        try {
            return ZoneId.of(candidate).getId();
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static String normalizeCountry(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return trimmed.length() == 2 ? trimmed.toUpperCase(Locale.ROOT) : null;
    }
}
