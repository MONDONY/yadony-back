package com.yadony.api.flights;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Recherche de vols validée et normalisée : codes IATA en majuscules, devise en
 * majuscules et dans la liste autorisée, date de départ jamais passée.
 *
 * @param departure  jour ou mois de départ, {@code null} pour toutes les dates à venir
 * @param returnDate présent = aller-retour, {@code null} = aller simple
 */
public record FlightQuery(String origin, String destination, FlightDate departure,
                          FlightDate returnDate, boolean directOnly, String currency) {

    static final String DEFAULT_CURRENCY = "EUR";

    /** Devises des corridors Yadony, toutes acceptées par Travelpayouts. */
    static final Set<String> CURRENCIES = Set.of("EUR", "XOF", "XAF", "USD", "GBP", "CAD", "CHF", "MAD");

    private static final Pattern IATA = Pattern.compile("^[A-Z]{3}$");

    public boolean roundTrip() {
        return returnDate != null;
    }

    /** Clé de cache et de regroupement : deux requêtes identiques partagent un seul appel. */
    String key() {
        return origin + "-" + destination + "|" + departure + "|" + returnDate + "|" + directOnly + "|" + currency;
    }

    static FlightQuery of(String origin, String destination, String departure, String returnDate,
                          boolean directOnly, String currency, Clock clock) {
        String from = iata(origin, "origin");
        String to = iata(destination, "destination");
        if (from.equals(to)) {
            throw FlightErrors.invalid("flights-same-airport", "Le départ et l'arrivée doivent être différents.");
        }
        LocalDate today = LocalDate.now(clock);
        FlightDate out = date(departure, "departureDate");
        if (out != null && out.last().isBefore(today)) {
            throw FlightErrors.invalid("flights-date-past", "La date de départ est déjà passée.");
        }
        FlightDate back = date(returnDate, "returnDate");
        if (back != null && out != null && back.last().isBefore(out.first())) {
            throw FlightErrors.invalid("flights-return-before-departure",
                    "Le retour ne peut pas précéder le départ.");
        }
        if (back != null && back.last().isBefore(today)) {
            throw FlightErrors.invalid("flights-date-past", "La date de retour est déjà passée.");
        }
        return new FlightQuery(from, to, out, back, directOnly, currency(currency));
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

    private static FlightDate date(String raw, String field) {
        try {
            return FlightDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw FlightErrors.invalid("flights-invalid-date",
                    field + " doit être au format AAAA-MM-JJ (jour) ou AAAA-MM (mois entier).");
        }
    }
}
