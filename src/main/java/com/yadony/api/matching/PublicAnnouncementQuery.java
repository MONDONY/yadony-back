package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Critères normalisés et validés de la recherche publique d'annonces.
 * Sert de clé de cache : deux saisies équivalentes donnent des critères égaux.
 * Les accents des villes sont conservés, car la recherche existante compare
 * {@code lower(ville) = lower(saisie)}.
 */
public record PublicAnnouncementQuery(
        String departureCity,
        String arrivalCity,
        LocalDate dateFrom,
        LocalDate dateTo,
        BigDecimal minAvailableKg,
        BigDecimal maxPricePerKg,
        BigDecimal minRating,
        Boolean urgent,
        String transportMode,
        String contentType,
        ItemSize minItemSize,
        String viaCity,
        BigDecimal minVolumeM3,
        BigDecimal maxPricePerM3,
        LocalDate arrivalBefore,
        int page) {

    public static final int MAX_CITY_LENGTH = 80;
    public static final int MAX_CONTENT_TYPE_LENGTH = 40;
    public static final int MAX_PAGE = 4;
    public static final long MAX_RANGE_DAYS = 90;
    private static final BigDecimal MAX_RATING = new BigDecimal("5");
    /** Borne haute de {@code minAvailableKg}. */
    public static final BigDecimal MAX_MIN_AVAILABLE_KG = new BigDecimal("1000");
    /** Borne haute de {@code maxPricePerKg} (couvre un prix au kilo en F CFA). */
    public static final BigDecimal MAX_PRICE_PER_KG = new BigDecimal("100000");
    /** Borne haute de {@code minVolumeM3} (la capacité d'un trajet est plafonnée à 9999 m³). */
    public static final BigDecimal MAX_MIN_VOLUME_M3 = new BigDecimal("9999");
    /** Borne haute de {@code maxPricePerM3} (couvre un prix au m³ en F CFA). */
    public static final BigDecimal MAX_PRICE_PER_M3 = new BigDecimal("10000000");
    /** Traversée maximale d'un trajet (120 j, bateau, {@link ArrivalRules}) : arrivée au plus tard départ + 120 j. */
    public static final long MAX_TRANSIT_DAYS = 120;
    /** Décimales admises sur les paramètres numériques ({@code 1E-999999} est donc refusé). */
    public static final int MAX_DECIMALS = 4;

    /** Sans taille minimale ni ville de passage. */
    public static PublicAnnouncementQuery of(String departureCity, String arrivalCity,
                                             LocalDate dateFrom, LocalDate dateTo,
                                             BigDecimal minAvailableKg, BigDecimal maxPricePerKg,
                                             BigDecimal minRating, Boolean urgent,
                                             String transportMode, String contentType,
                                             Integer page, LocalDate today) {
        return of(departureCity, arrivalCity, dateFrom, dateTo, minAvailableKg, maxPricePerKg, minRating,
                urgent, transportMode, contentType, null, null, page, today);
    }

    /** Sans filtre bateau (volume, prix au m³, arrivée). */
    public static PublicAnnouncementQuery of(String departureCity, String arrivalCity,
                                             LocalDate dateFrom, LocalDate dateTo,
                                             BigDecimal minAvailableKg, BigDecimal maxPricePerKg,
                                             BigDecimal minRating, Boolean urgent,
                                             String transportMode, String contentType,
                                             String minItemSize, String viaCity,
                                             Integer page, LocalDate today) {
        return of(departureCity, arrivalCity, dateFrom, dateTo, minAvailableKg, maxPricePerKg, minRating,
                urgent, transportMode, contentType, minItemSize, viaCity, null, null, null, page, today);
    }

    public static PublicAnnouncementQuery of(String departureCity, String arrivalCity,
                                             LocalDate dateFrom, LocalDate dateTo,
                                             BigDecimal minAvailableKg, BigDecimal maxPricePerKg,
                                             BigDecimal minRating, Boolean urgent,
                                             String transportMode, String contentType,
                                             String minItemSize, String viaCity,
                                             BigDecimal minVolumeM3, BigDecimal maxPricePerM3,
                                             LocalDate arrivalBefore,
                                             Integer page, LocalDate today) {
        // Annonces ouvertes et à venir : un dateFrom passé est ramené à aujourd'hui, et la plage
        // de 90 jours se mesure depuis cette date ramenée.
        LocalDate from = dateFrom != null && dateFrom.isAfter(today) ? dateFrom : today;
        LocalDate to = dateTo != null ? dateTo : from.plusDays(MAX_RANGE_DAYS);
        if (from.isAfter(to)) {
            throw invalid("dateFrom must not be after dateTo");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw invalid("Date range must not exceed " + MAX_RANGE_DAYS + " days");
        }
        BigDecimal kg = boundedDecimal(minAvailableKg, "minAvailableKg", MAX_MIN_AVAILABLE_KG);
        BigDecimal price = boundedDecimal(maxPricePerKg, "maxPricePerKg", MAX_PRICE_PER_KG);
        BigDecimal rating = boundedDecimal(minRating, "minRating", MAX_RATING);
        BigDecimal volume = boundedDecimal(minVolumeM3, "minVolumeM3", MAX_MIN_VOLUME_M3);
        BigDecimal pricePerM3 = boundedDecimal(maxPricePerM3, "maxPricePerM3", MAX_PRICE_PER_M3);
        int p = page == null ? 0 : page;
        if (p < 0 || p > MAX_PAGE) {
            throw invalid("page must be between 0 and " + MAX_PAGE);
        }
        return new PublicAnnouncementQuery(
                normaliseCity(departureCity, "departureCity"),
                normaliseCity(arrivalCity, "arrivalCity"),
                from, to, kg, price, rating, urgent,
                normaliseTransportMode(transportMode),
                normaliseContentType(contentType),
                normaliseMinItemSize(minItemSize),
                normaliseCity(viaCity, "viaCity"),
                volume, pricePerM3, clampArrival(arrivalBefore, from, to),
                p);
    }

    /**
     * Ramène {@code arrivalBefore} dans [{@code from} - 1, {@code to} + {@link #MAX_TRANSIT_DAYS}] : les trajets
     * retenus partent entre {@code from} et {@code to} et arrivent au plus 120 jours après leur départ, donc
     * une date au-delà de {@code to} + 120 j donne les mêmes résultats que cette borne, et une date avant
     * {@code from} ne donne aucun résultat (un trajet n'arrive pas avant son départ) : elle est ramenée à la
     * veille de {@code from}, qui reste vide, et non à {@code from} qui renverrait des trajets. Le clamp borne
     * le nombre de clés de cache qu'un anonyme peut créer avec une date libre.
     */
    private static LocalDate clampArrival(LocalDate value, LocalDate from, LocalDate to) {
        if (value == null) {
            return null;
        }
        LocalDate min = from.minusDays(1);
        LocalDate max = to.plusDays(MAX_TRANSIT_DAYS);
        if (value.isBefore(min)) {
            return min;
        }
        return value.isAfter(max) ? max : value;
    }

    /** {@code strip}, insensible à la casse, puis le nom exact de {@link TransportMode} ; inconnu = 400. */
    private static String normaliseTransportMode(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        for (TransportMode mode : TransportMode.values()) {
            if (mode.name().equals(upper)) {
                return mode.name();
            }
        }
        throw invalid("transportMode must be one of " + java.util.Arrays.toString(TransportMode.values()));
    }

    /** {@code strip}, insensible à la casse, puis S, M, L ou XL ; inconnu = 400 (comme le mode de transport). */
    private static ItemSize normaliseMinItemSize(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        ItemSize size = ItemSize.parseOrNull(trimmed);
        if (size == null) {
            throw invalid("minItemSize must be one of " + java.util.Arrays.toString(ItemSize.values()));
        }
        return size;
    }

    private static String normaliseContentType(String value) {
        String trimmed = blankToNull(value);
        if (trimmed != null && trimmed.length() > MAX_CONTENT_TYPE_LENGTH) {
            throw invalid("contentType must not exceed " + MAX_CONTENT_TYPE_LENGTH + " characters");
        }
        return trimmed;
    }

    private static String normaliseCity(String value, String field) {
        if (value == null) {
            return null;
        }
        String normalised = value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (normalised.isEmpty()) {
            return null;
        }
        if (normalised.length() > MAX_CITY_LENGTH) {
            throw invalid(field + " must not exceed " + MAX_CITY_LENGTH + " characters");
        }
        return normalised;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Borne un paramètre décimal AVANT tout calcul : Spring lie {@code 1E+20000000} (12 octets)
     * par {@code new BigDecimal}, et le premier {@code setScale} en aval matérialise alors un
     * entier de 20 millions de chiffres. {@code signum}, {@code compareTo} et {@code scale} ne
     * lisent que le signe et l'exposant : ils restent instantanés même sur un exposant géant.
     * La valeur rendue est sans zéros de queue, pour que {@code 1.0} et {@code 1} donnent la
     * même clé de cache.
     */
    static BigDecimal boundedDecimal(BigDecimal value, String field, BigDecimal max) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0 || value.compareTo(max) > 0) {
            throw invalid(field + " must be between 0 and " + max.toPlainString());
        }
        if (value.scale() > MAX_DECIMALS) {
            throw invalid(field + " must not have more than " + MAX_DECIMALS + " decimals");
        }
        return value.stripTrailingZeros();
    }

    private static YadonyBusinessException invalid(String detail) {
        return new YadonyBusinessException(HttpStatus.BAD_REQUEST, "invalid-search", "Invalid Search", detail);
    }
}
