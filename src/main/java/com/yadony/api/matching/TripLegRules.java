package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.AnnouncementRequest;
import org.springframework.http.HttpStatus;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chaînage des étapes d'un voyage (FLUTTER-4D). Chaque étape reste une annonce
 * ordinaire, validée par ailleurs comme toute annonce ; ces règles ne portent que sur
 * le lien entre deux étapes consécutives :
 * <ul>
 *   <li>la ville de départ d'une étape est la ville d'arrivée de la précédente
 *       (casse, espaces et accents ignorés) ;</li>
 *   <li>une étape part au plus tôt le jour d'arrivée de la précédente et, ce même jour,
 *       pas avant son heure d'arrivée quand les deux heures sont connues ;</li>
 *   <li>toutes les étapes sont publiées, ou toutes enregistrées en brouillon.</li>
 * </ul>
 * Chaque refus porte la propriété {@code legIndex} (rang de l'étape fautive, à partir
 * de 1) pour que l'app puisse désigner l'étape à corriger.
 */
public final class TripLegRules {

    public static final int MIN_LEGS = 2;
    public static final int MAX_LEGS = 5;

    private TripLegRules() {}

    public static void validate(List<AnnouncementRequest> legs) {
        if (legs == null || legs.size() < MIN_LEGS || legs.size() > MAX_LEGS) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "trip-legs-count", "Nombre d'étapes invalide",
                    "Un voyage compte de " + MIN_LEGS + " à " + MAX_LEGS + " étapes");
        }
        boolean draft = legs.get(0).isDraft();
        for (int i = 1; i < legs.size(); i++) {
            AnnouncementRequest previous = legs.get(i - 1);
            AnnouncementRequest leg = legs.get(i);
            int legIndex = i + 1;

            if (leg.isDraft() != draft) {
                throw refusal(legIndex, "trip-legs-draft-mismatch", "Étapes incohérentes",
                        "Toutes les étapes d'un voyage sont publiées ensemble, ou toutes en brouillon");
            }
            if (!sameCity(previous.arrivalCity(), leg.departureCity())) {
                throw refusal(legIndex, "trip-leg-city-mismatch", "Étape non chaînée",
                        "L'étape " + legIndex + " doit partir de " + previous.arrivalCity()
                                + ", la ville d'arrivée de l'étape précédente");
            }
            LocalDate previousArrival = previous.arrivalDate() != null
                    ? previous.arrivalDate() : previous.departureDate();
            LocalDate departure = leg.departureDate();
            if (previousArrival != null && departure != null) {
                boolean before = departure.isBefore(previousArrival)
                        || (departure.isEqual(previousArrival)
                            && previous.arrivalTime() != null && leg.departureTime() != null
                            && leg.departureTime().isBefore(previous.arrivalTime()));
                if (before) {
                    throw refusal(legIndex, "trip-leg-date-before-previous", "Étape trop tôt",
                            "L'étape " + legIndex + " ne peut pas partir avant l'arrivée de l'étape précédente");
                }
            }
        }
    }

    /** Ajoute le rang de l'étape à un refus levé par la validation d'une annonce. */
    static YadonyBusinessException withLegIndex(YadonyBusinessException e, int legIndex) {
        Map<String, Object> props = new java.util.HashMap<>(e.getProperties());
        props.put("legIndex", legIndex);
        return new YadonyBusinessException(e.getStatus(), e.getErrorCode(), e.getTitle(),
                e.getMessage(), Map.copyOf(props));
    }

    static boolean sameCity(String a, String b) {
        return a != null && b != null && normalize(a).equals(normalize(b));
    }

    private static String normalize(String city) {
        String stripped = Normalizer.normalize(city.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return stripped.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static YadonyBusinessException refusal(int legIndex, String code, String title, String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, title, detail,
                Map.of("legIndex", legIndex));
    }
}
