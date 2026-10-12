package com.yadony.api.flights;

import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Recherche de vols validée et normalisée : codes IATA en majuscules, devise en
 * majuscules et dans la liste autorisée, mois de départ jamais passé.
 *
 * @param returnMonth présent = aller-retour, {@code null} = aller simple
 */
public record FlightQuery(String origin, String destination, YearMonth departureMonth,
                          YearMonth returnMonth, boolean directOnly, String currency) {

    static final String DEFAULT_CURRENCY = "EUR";

    /** Devises des corridors Yadony, toutes acceptées par Travelpayouts. */
    static final Set<String> CURRENCIES = Set.of("EUR", "XOF", "XAF", "USD", "GBP", "CAD", "CHF", "MAD");

    private static final Pattern IATA = Pattern.compile("^[A-Z]{3}$");

    public boolean roundTrip() {
        return returnMonth != null;
    }

    /** Clé de cache et de regroupement : deux requêtes identiques partagent un seul appel. */
    String key() {
        return origin + "-" + destination + "|" + departureMonth + "|" + returnMonth + "|" + directOnly + "|" + currency;
    }

    static FlightQuery of(String origin, String destination, String departureMonth, String returnMonth,
                          boolean directOnly, String currency, Clock clock) {
        String from = iata(origin, "origin");
        String to = iata(destination, "destination");
        if (from.equals(to)) {
            throw FlightErrors.invalid("flights-same-airport", "Le départ et l'arrivée doivent être différents.");
        }
        YearMonth now = YearMonth.now(clock);
        YearMonth departure = month(departureMonth, "departureMonth");
        if (departure != null && departure.isBefore(now)) {
            throw FlightErrors.invalid("flights-month-past", "Le mois de départ est déjà passé.");
        }
        YearMonth back = month(returnMonth, "returnMonth");
        if (back != null && departure != null && back.isBefore(departure)) {
            throw FlightErrors.invalid("flights-return-before-departure",
                    "Le retour ne peut pas précéder le départ.");
        }
        if (back != null && back.isBefore(now)) {
            throw FlightErrors.invalid("flights-month-past", "Le mois de retour est déjà passé.");
        }
        return new FlightQuery(from, to, departure, back, directOnly, currency(currency));
    }

    static String iata(String raw, String field) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!IATA.matcher(code).matches()) {
            throw FlightErrors.invalid("flights-invalid-code", field + " doit être un code de 3 lettres (ex. PAR).");
        }
        return code;
    }

    static String currency(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_CURRENCY;
        String c = raw.trim().toUpperCase(Locale.ROOT);
        if (!CURRENCIES.contains(c)) {
            throw FlightErrors.invalid("flights-invalid-currency", "Devise non prise en charge : " + c + ".");
        }
        return c;
    }

    private static YearMonth month(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw FlightErrors.invalid("flights-invalid-month", field + " doit être au format AAAA-MM.");
        }
    }
}
