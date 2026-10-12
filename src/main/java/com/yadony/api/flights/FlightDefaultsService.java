package com.yadony.api.flights;

import com.yadony.api.flights.FlightCorridorQueryRepository.CodePair;
import com.yadony.api.flights.dto.FlightDefaultsDto;
import com.yadony.api.flights.dto.FlightPlaceDto;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Préremplissage du formulaire : la dernière recherche de billets de l'utilisateur, à défaut
 * son corridor le plus récent. Sans {@code @Transactional} : l'appel d'autocomplétion pour
 * les libellés ne tient aucune connexion JDBC.
 */
@Service
public class FlightDefaultsService {

    private static final FlightDefaultsDto NONE = new FlightDefaultsDto("none", null, null);

    private final FlightCorridorQueryRepository corridors;
    private final FlightDealService deals;
    private final FlightPlaceService places;
    private final FlightUsers users;

    public FlightDefaultsService(FlightCorridorQueryRepository corridors, FlightDealService deals,
                                 FlightPlaceService places, FlightUsers users) {
        this.corridors = corridors;
        this.deals = deals;
        this.places = places;
        this.users = users;
    }

    public FlightDefaultsDto defaults(String firebaseUid) {
        FlightUsers.Caller caller = users.resolve(firebaseUid);
        if (caller.anonymous()) return NONE;

        List<CodePair> searched = corridors.recentSearchedRoutes(caller.id(), 1);
        if (!searched.isEmpty()) {
            return of("search", searched.get(0), caller.language());
        }
        List<CodePair> personal = deals.personalRoutes(caller.id());
        if (!personal.isEmpty()) {
            return of("corridor", personal.get(0), caller.language());
        }
        return NONE;
    }

    private FlightDefaultsDto of(String source, CodePair route, String language) {
        return new FlightDefaultsDto(source, place(route.origin(), language), place(route.destination(), language));
    }

    /** Libellé de la ville si l'autocomplétion le connaît, sinon le code seul : jamais bloquant. */
    private FlightPlaceDto place(String code, String language) {
        try {
            return places.suggest(code, language).stream()
                    .filter(p -> code.equals(p.code()))
                    .findFirst()
                    .orElse(new FlightPlaceDto(code, code, null, null));
        } catch (RuntimeException e) {
            return new FlightPlaceDto(code, code, null, null);
        }
    }
}
