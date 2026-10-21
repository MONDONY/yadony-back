package com.yadony.api.matching.ranking;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.TransportMode;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Évalue en mémoire quels critères souples d'une recherche un trajet ne respecte pas.
 * Reprend la sémantique de {@code AnnouncementSpecification} (mêmes bornes, mêmes exceptions) ;
 * toute évolution d'un filtre SQL doit être reportée ici.
 */
public final class SearchCriteriaMatcher {

    private SearchCriteriaMatcher() {
    }

    /** Liste vide = le trajet respecte tous les critères souples. */
    public static List<UnmetCriterion> unmet(AnnouncementEntity a, BigDecimal travelerAverageRating,
                                             boolean travelerIsPro, SearchCriteria c) {
        List<UnmetCriterion> out = new ArrayList<>();
        LocalDate date = a.getDepartureDate();

        // DATE : bornes inclusives, comme departureDateFrom / departureDateTo
        if (c.dateFrom() != null && date.isBefore(c.dateFrom())) {
            out.add(new UnmetCriterion("DATE", c.dateFrom().toString(), date.toString()));
        } else if (c.dateTo() != null && date.isAfter(c.dateTo())) {
            out.add(new UnmetCriterion("DATE", c.dateTo().toString(), date.toString()));
        }

        // AVAILABLE_KG : le kilo libre passe toujours la borne basse, jamais exempté de la haute
        BigDecimal kg = a.getAvailableKg();
        if (c.minAvailableKg() != null && a.getCapacityUnit() != CapacityUnit.KG_FREE
                && kg.compareTo(c.minAvailableKg()) < 0) {
            out.add(new UnmetCriterion("AVAILABLE_KG", plain(c.minAvailableKg()), plain(kg)));
        } else if (c.maxAvailableKg() != null && kg.compareTo(c.maxAvailableKg()) > 0) {
            out.add(new UnmetCriterion("AVAILABLE_KG", plain(c.maxAvailableKg()), plain(kg)));
        }

        // TRANSPORT_MODE : valeur invalide ignorée, comme dans la recherche SQL
        if (c.transportMode() != null && !c.transportMode().isBlank()) {
            TransportMode wanted = parseMode(c.transportMode());
            if (wanted != null && wanted != a.getTransportMode()) {
                out.add(new UnmetCriterion("TRANSPORT_MODE", wanted.name(),
                        a.getTransportMode() == null ? "" : a.getTransportMode().name()));
            }
        }

        // MIN_RATING : un voyageur sans note n'atteint aucun minimum
        if (c.minRating() != null
                && (travelerAverageRating == null || travelerAverageRating.compareTo(c.minRating()) < 0)) {
            out.add(new UnmetCriterion("MIN_RATING", plain(c.minRating()),
                    travelerAverageRating == null ? "" : plain(travelerAverageRating)));
        }

        // CONTENT_TYPE : appartenance à acceptedContentTypes
        if (c.contentType() != null && !c.contentType().isBlank()) {
            List<String> accepted = a.getAcceptedContentTypes() == null ? List.of() : a.getAcceptedContentTypes();
            if (!accepted.contains(c.contentType())) {
                out.add(new UnmetCriterion("CONTENT_TYPE", c.contentType(), String.join(", ", accepted)));
            }
        }

        // RADIUS : null = pas de filtre
        if (c.idsInRadius() != null && !c.idsInRadius().contains(a.getId())) {
            out.add(new UnmetCriterion("RADIUS", "", ""));
        }

        // WEEKEND_ONLY : samedi ou dimanche (dow 6 ou 0 en SQL)
        if (c.weekendOnly()) {
            DayOfWeek d = date.getDayOfWeek();
            if (d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY) {
                out.add(new UnmetCriterion("WEEKEND_ONLY", "", date.getDayOfWeek().name()));
            }
        }

        // URGENT : fenêtre [aujourd'hui (UTC), urgentTo]
        if (c.urgent() && c.urgentTo() != null
                && (date.isAfter(c.urgentTo()) || date.isBefore(LocalDate.now(ZoneOffset.UTC)))) {
            out.add(new UnmetCriterion("URGENT", c.urgentTo().toString(), date.toString()));
        }

        // KILO_PRO_ONLY : voyageur Pro uniquement
        if (c.kiloProOnly() && !travelerIsPro) {
            out.add(new UnmetCriterion("KILO_PRO_ONLY", "", ""));
        }
        return out;
    }

    private static TransportMode parseMode(String raw) {
        try {
            return TransportMode.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
