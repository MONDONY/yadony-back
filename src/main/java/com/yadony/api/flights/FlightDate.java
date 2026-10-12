package com.yadony.api.flights;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/**
 * Date de départ ou de retour d'une recherche : un jour précis ({@code 2026-12-12}) ou un
 * mois entier ({@code 2026-12}). Travelpayouts accepte les deux formes pour
 * {@code departure_at} et {@code return_at}.
 *
 * <p>Exactement un des deux champs est renseigné.
 */
public record FlightDate(LocalDate day, YearMonth month) {

    public FlightDate {
        if ((day == null) == (month == null)) {
            throw new IllegalArgumentException("Un jour ou un mois, pas les deux");
        }
    }

    public static FlightDate ofDay(LocalDate day) {
        return new FlightDate(day, null);
    }

    public static FlightDate ofMonth(YearMonth month) {
        return new FlightDate(null, month);
    }

    /**
     * @param raw {@code AAAA-MM-JJ} ou {@code AAAA-MM}, {@code null} ou vide pour « sans date »
     * @throws DateTimeParseException si le texte n'est dans aucun des deux formats
     */
    public static FlightDate parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.length() > 7) {
            return ofDay(LocalDate.parse(value));
        }
        return ofMonth(YearMonth.parse(value));
    }

    public boolean isDay() {
        return day != null;
    }

    /** Premier jour couvert. */
    public LocalDate first() {
        return day != null ? day : month.atDay(1);
    }

    /** Dernier jour couvert. */
    public LocalDate last() {
        return day != null ? day : month.atEndOfMonth();
    }

    /** Valeur envoyée au partenaire et utilisée dans les clés de cache. */
    public String providerValue() {
        return day != null ? day.toString() : month.toString();
    }

    @Override
    public String toString() {
        return providerValue();
    }
}
