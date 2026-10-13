package com.yadony.api.flights;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Repli « dates proches » d'une recherche en jours précis.
 *
 * <p>{@code prices_for_dates} ne lit que le cache d'Aviasales : un couple exact (ex. Paris → Abidjan
 * le 29/11, retour le 27/12) que personne n'a cherché récemment n'y figure pas, alors que le même
 * trajet existe à un ou deux jours près. On relit alors le cache sur les mois couverts par la
 * fenêtre (voir {@link #monthQueries}) et {@link #filter} ne garde que les offres dont l'aller ET
 * le retour tombent à {@code window} jours au plus des jours demandés.
 *
 * <p>Deux fenêtres, essayées dans l'ordre : {@link #WINDOW_DAYS} jours, puis {@link #WIDE_WINDOW_DAYS}
 * si la première ne trouve rien. La fenêtre retenue est rendue à l'app ({@code nearbyDaysWindow}).
 *
 * <p>Les dates comparées sont les dates locales telles qu'écrites dans {@code departure_at} et
 * {@code return_at} ({@code 2026-12-24T23:30:00+00:00} → le 24), pas leur conversion en UTC :
 * c'est le jour que l'utilisateur lira sur son billet.
 */
final class FlightNearbyDates {

    /** Écart maximal, en jours, de part et d'autre de chaque date demandée. */
    static final int WINDOW_DAYS = 3;
    /** Seconde fenêtre, quand rien n'existe à {@link #WINDOW_DAYS} jours : une semaine. */
    static final int WIDE_WINDOW_DAYS = 7;
    /** Fenêtres essayées dans l'ordre. */
    static final int[] WINDOWS = {WINDOW_DAYS, WIDE_WINDOW_DAYS};

    private FlightNearbyDates() {
    }

    /**
     * Les recherches au mois qui couvrent la fenêtre : chaque mois touché par
     * {@code [départ - window, départ + window]} croisé avec chaque mois touché par la fenêtre du
     * retour. Le départ du 29/11 (fenêtre 26/11 → 02/12) et le retour du 27/12 donnent
     * {@code 2026-11 × 2026-12} et {@code 2026-12 × 2026-12} : interroger le seul mois du jour demandé
     * manquait les départs du 1er et du 2 décembre.
     *
     * <p>Une fenêtre de moins d'un mois touche deux mois au plus : quatre recherches au plus.
     * Les mois entièrement passés et les couples où le retour précède le départ sont écartés.
     * Une date déjà au mois, ou absente (aller simple), est reprise telle quelle.
     */
    static List<FlightQuery> monthQueries(FlightQuery requested, int window, LocalDate today) {
        List<FlightDate> outMonths = months(requested.departure(), window, today);
        List<FlightDate> backMonths = months(requested.returnDate(), window, today);
        List<FlightQuery> queries = new ArrayList<>();
        for (FlightDate out : outMonths) {
            for (FlightDate back : backMonths) {
                if (out != null && back != null && back.month().isBefore(out.month())) continue;
                queries.add(new FlightQuery(requested.origin(), requested.destination(), out, back,
                        requested.directOnly(), requested.currency()));
            }
        }
        return queries;
    }

    private static List<FlightDate> months(FlightDate date, int window, LocalDate today) {
        List<FlightDate> months = new ArrayList<>();
        if (date == null || !date.isDay()) {
            months.add(date);
            return months;
        }
        YearMonth last = YearMonth.from(date.day().plusDays(window));
        for (YearMonth m = YearMonth.from(date.day().minusDays(window)); !m.isAfter(last); m = m.plusMonths(1)) {
            if (!m.atEndOfMonth().isBefore(today)) months.add(FlightDate.ofMonth(m));
        }
        return months;
    }

    /**
     * Les offres dont l'aller est à {@code window} jours au plus du départ demandé et, pour un
     * aller-retour, le retour à {@code window} jours au plus du retour demandé. Une date demandée
     * au mois n'est pas filtrée. L'ordre n'est pas garanti : {@link FlightPriceService#merge}
     * dédoublonne et trie par prix ensuite.
     *
     * @param requested la recherche d'origine, avec ses jours précis
     * @param offers    les offres des recherches au mois
     */
    static List<TravelpayoutsPrice> filter(FlightQuery requested, List<TravelpayoutsPrice> offers, int window) {
        LocalDate out = day(requested.departure());
        LocalDate back = day(requested.returnDate());
        return offers.stream()
                .filter(Objects::nonNull)
                .filter(p -> p.price() != null)
                .filter(p -> within(out, p.departureAt(), window) && within(back, p.returnAt(), window))
                .toList();
    }

    /** Le jour demandé, ou {@code null} si la date était un mois entier ou absente (rien à filtrer). */
    private static LocalDate day(FlightDate date) {
        return date != null && date.isDay() ? date.day() : null;
    }

    private static boolean within(LocalDate wanted, String providerDateTime, int window) {
        if (wanted == null) return true;
        LocalDate actual = localDate(providerDateTime);
        return actual != null && Math.abs(ChronoUnit.DAYS.between(wanted, actual)) <= window;
    }

    /** Les dix premiers caractères ({@code AAAA-MM-JJ}) : la date à l'heure du lieu, sans conversion. */
    static LocalDate localDate(String providerDateTime) {
        if (providerDateTime == null || providerDateTime.length() < 10) return null;
        try {
            return LocalDate.parse(providerDateTime.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
