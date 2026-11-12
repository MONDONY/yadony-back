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

    public static PublicAnnouncementQuery of(String departureCity, String arrivalCity,
                                             LocalDate dateFrom, LocalDate dateTo,
                                             BigDecimal minAvailableKg, BigDecimal maxPricePerKg,
                                             BigDecimal minRating, Boolean urgent,
                                             String transportMode, String contentType,
                                             String minItemSize, String viaCity,
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
                p);
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
    private static BigDecimal boundedDecimal(BigDecimal value, String field, BigDecimal max) {
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
