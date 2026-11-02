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
        int page) {

    public static final int MAX_CITY_LENGTH = 80;
    public static final int MAX_PAGE = 4;
    public static final long MAX_RANGE_DAYS = 90;
    private static final BigDecimal MAX_RATING = new BigDecimal("5");

    public static PublicAnnouncementQuery of(String departureCity, String arrivalCity,
                                             LocalDate dateFrom, LocalDate dateTo,
                                             BigDecimal minAvailableKg, BigDecimal maxPricePerKg,
                                             BigDecimal minRating, Boolean urgent,
                                             String transportMode, String contentType,
                                             Integer page, LocalDate today) {
        LocalDate from = dateFrom != null ? dateFrom : today;
        LocalDate to = dateTo != null ? dateTo : from.plusDays(MAX_RANGE_DAYS);
        if (from.isAfter(to)) {
            throw invalid("dateFrom must not be after dateTo");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw invalid("Date range must not exceed " + MAX_RANGE_DAYS + " days");
        }
        requireNotNegative(minAvailableKg, "minAvailableKg");
        requireNotNegative(maxPricePerKg, "maxPricePerKg");
        if (minRating != null
                && (minRating.signum() < 0 || minRating.compareTo(MAX_RATING) > 0)) {
            throw invalid("minRating must be between 0 and 5");
        }
        int p = page == null ? 0 : page;
        if (p < 0 || p > MAX_PAGE) {
            throw invalid("page must be between 0 and " + MAX_PAGE);
        }
        return new PublicAnnouncementQuery(
                normaliseCity(departureCity, "departureCity"),
                normaliseCity(arrivalCity, "arrivalCity"),
                from, to, minAvailableKg, maxPricePerKg, minRating, urgent,
                blankToNull(transportMode, true),
                blankToNull(contentType, false),
                p);
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

    private static String blankToNull(String value, boolean upperCase) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return upperCase ? trimmed.toUpperCase(Locale.ROOT) : trimmed;
    }

    private static void requireNotNegative(BigDecimal value, String field) {
        if (value != null && value.signum() < 0) {
            throw invalid(field + " must not be negative");
        }
    }

    private static YadonyBusinessException invalid(String detail) {
        return new YadonyBusinessException(HttpStatus.BAD_REQUEST, "invalid-search", "Invalid Search", detail);
    }
}
