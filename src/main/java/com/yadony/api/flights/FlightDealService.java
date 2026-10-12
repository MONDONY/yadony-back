package com.yadony.api.flights;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yadony.api.flights.FlightCorridorQueryRepository.CityPair;
import com.yadony.api.flights.FlightCorridorQueryRepository.CodePair;
import com.yadony.api.flights.dto.FlightDealDto;
import com.yadony.api.flights.dto.FlightDealsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Bons plans de l'utilisateur : le prix le plus bas de chacun de ses corridors, retenu
 * seulement s'il passe le seuil de {@link FlightDealCalculator}.
 *
 * <p>Ses corridors, du plus récent au plus ancien : ses recherches de vols, puis ses
 * trajets, demandes d'envoi et alertes corridor (villes converties en codes IATA). Sans
 * aucun corridor exploitable, la liste par défaut des axes de la diaspora prend le relais.
 *
 * <p>Sans {@code @Transactional} : les appels réseau ne tiennent aucune connexion JDBC.
 */
@Service
public class FlightDealService {

    private static final Logger log = LoggerFactory.getLogger(FlightDealService.class);

    static final int MAX_ROUTES = 8;
    private static final int MAX_DEALS = 10;

    private final FlightPriceService prices;
    private final FlightPlaceService places;
    private final FlightCorridorQueryRepository corridors;
    private final FlightOfferMapper mapper;
    private final FlightUsers users;
    private final List<CodePair> defaultRoutes;
    private final Cache<String, FlightDealsResponse> responses = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(15))
            .maximumSize(10_000)
            .build();

    public FlightDealService(FlightPriceService prices, FlightPlaceService places,
                             FlightCorridorQueryRepository corridors, FlightOfferMapper mapper, FlightUsers users,
                             @Value("${yadony.flights.default-routes:PAR-ABJ,PAR-DKR,PAR-BKO,PAR-DLA,PAR-CMN,PAR-COO,PAR-CKY,PAR-OUA}")
                             String defaultRoutes) {
        this.prices = prices;
        this.places = places;
        this.corridors = corridors;
        this.mapper = mapper;
        this.users = users;
        this.defaultRoutes = parseRoutes(defaultRoutes);
    }

    public FlightDealsResponse deals(String firebaseUid, String currencyParam) {
        if (!prices.isAvailable()) throw FlightErrors.unavailable();
        String currency = FlightQuery.currency(currencyParam);
        FlightUsers.Caller caller = users.resolve(firebaseUid);
        String cacheKey = (caller.anonymous() ? "guest" : caller.id()) + "|" + currency + "|" + caller.language();
        FlightDealsResponse cached = responses.getIfPresent(cacheKey);
        if (cached != null) return cached;

        List<CodePair> personal = caller.anonymous() ? List.of() : personalRoutes(caller.id());
        boolean personalized = !personal.isEmpty();
        List<CodePair> routes = personalized ? personal : defaultRoutes;

        List<FlightDealDto> deals = new ArrayList<>();
        int failures = 0;
        for (CodePair route : routes) {
            FlightQuery query = new FlightQuery(route.origin(), route.destination(), null, null, false, currency);
            try {
                FlightDealCalculator.evaluate(prices.prices(query)).ifPresent(deal -> deals.add(new FlightDealDto(
                        mapper.toDto(deal.cheapest(), currency, caller.language()),
                        deal.medianPrice(), deal.discountPercent())));
            } catch (FlightProviderException e) {
                failures++;
            }
        }
        if (failures == routes.size() && !routes.isEmpty()) {
            throw FlightErrors.providerError();
        }
        deals.sort(Comparator.comparingInt(FlightDealDto::discountPercent).reversed());
        FlightDealsResponse response = new FlightDealsResponse(currency, personalized,
                deals.stream().limit(MAX_DEALS).toList());
        if (failures == 0) responses.put(cacheKey, response);
        return response;
    }

    /** Ses trajets aériens, recherches d'abord, sans doublon ni aller = retour. */
    List<CodePair> personalRoutes(UUID userId) {
        Set<CodePair> routes = new LinkedHashSet<>();
        for (CodePair searched : corridors.recentSearchedRoutes(userId, MAX_ROUTES)) {
            routes.add(searched);
        }
        if (routes.size() < MAX_ROUTES) {
            for (CityPair pair : corridors.recentCityCorridors(userId, MAX_ROUTES * 2)) {
                if (routes.size() >= MAX_ROUTES) break;
                Optional<String> from = places.resolveCity(pair.departureCity());
                Optional<String> to = places.resolveCity(pair.arrivalCity());
                if (from.isPresent() && to.isPresent() && !from.get().equals(to.get())) {
                    routes.add(new CodePair(from.get(), to.get()));
                }
            }
        }
        return List.copyOf(routes);
    }

    static List<CodePair> parseRoutes(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<CodePair> routes = new ArrayList<>();
        for (String token : Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            String[] parts = token.toUpperCase(Locale.ROOT).split("-");
            if (parts.length == 2 && parts[0].matches("[A-Z]{3}") && parts[1].matches("[A-Z]{3}")) {
                routes.add(new CodePair(parts[0], parts[1]));
            } else {
                log.warn("Trajet par défaut ignoré (format AAA-BBB attendu) : {}", token);
            }
        }
        return List.copyOf(routes);
    }
}
