package com.yadony.api.flights;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Accès à l'API partenaire Travelpayouts (programme d'affiliation Aviasales).
 *
 * <p>Trois lectures seulement : les prix en cache d'Aviasales, l'autocomplétion des
 * villes et la liste des compagnies. Aucune vente ne passe par ici : l'utilisateur
 * réserve sur le site partenaire, Yadony touche une commission (pas d'agrément de vente).
 *
 * <p>Le token ne quitte jamais le back : l'app n'appelle que nos endpoints {@code /flights}.
 * Sans token configuré, {@link #isConfigured()} rend {@code false} et aucun appel ne part.
 */
@Component
public class TravelpayoutsClient {

    private static final Logger log = LoggerFactory.getLogger(TravelpayoutsClient.class);

    private final RestClient restClient;
    private final String token;
    private final String apiBaseUrl;
    private final String autocompleteUrl;

    @Autowired
    public TravelpayoutsClient(
            @Value("${yadony.flights.travelpayouts.token:}") String token,
            @Value("${yadony.flights.travelpayouts.api-base-url:https://api.travelpayouts.com}") String apiBaseUrl,
            @Value("${yadony.flights.travelpayouts.autocomplete-url:https://autocomplete.travelpayouts.com}")
            String autocompleteUrl) {
        this(withTimeouts(), token, apiBaseUrl, autocompleteUrl);
    }

    TravelpayoutsClient(RestClient restClient, String token, String apiBaseUrl, String autocompleteUrl) {
        this.restClient = restClient;
        this.token = token == null ? "" : token.trim();
        this.apiBaseUrl = apiBaseUrl;
        this.autocompleteUrl = autocompleteUrl;
    }

    /**
     * Timeouts durs : sans eux, {@code RestClient} hérite des défauts JDK (infinis) et un
     * appel pendu bloquerait une requête utilisateur ou l'unique thread de scheduling.
     */
    private static RestClient withTimeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return RestClient.builder().requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return !token.isEmpty();
    }

    /**
     * Prix les plus bas trouvés par Aviasales pour un trajet, triés par prix.
     *
     * @param departure  jour ou mois de départ, {@code null} pour toutes les dates à venir
     * @param returnDate jour ou mois de retour : présent = aller-retour, {@code null} = aller simple
     * @throws FlightProviderException si l'API est injoignable ou répond une erreur
     */
    public List<TravelpayoutsPrice> pricesForDates(String origin, String destination,
                                                   FlightDate departure, FlightDate returnDate,
                                                   boolean directOnly, String currency, int limit) {
        boolean oneWay = returnDate == null;
        URI uri = uri(apiBaseUrl, b -> {
            b.path("/aviasales/v3/prices_for_dates")
                    .queryParam("origin", origin)
                    .queryParam("destination", destination)
                    .queryParam("currency", currency.toLowerCase(Locale.ROOT))
                    .queryParam("sorting", "price")
                    .queryParam("direct", directOnly)
                    .queryParam("one_way", oneWay)
                    .queryParam("unique", false)
                    .queryParam("limit", limit)
                    .queryParam("page", 1)
                    .queryParam("token", token);
            if (departure != null) b.queryParam("departure_at", departure.providerValue());
            if (returnDate != null) b.queryParam("return_at", returnDate.providerValue());
            return b;
        });
        PricesResponse body = get(uri, PricesResponse.class, "prix " + origin + "-" + destination);
        if (body == null || !body.success() || body.data() == null) {
            throw new FlightProviderException("Réponse prix invalide pour " + origin + "-" + destination);
        }
        return body.data();
    }

    /** Suggestions de villes et d'aéroports pour un début de nom. */
    public List<TravelpayoutsPlace> places(String term, String locale) {
        URI uri = uri(autocompleteUrl, b -> b.path("/places2")
                .queryParam("term", term)
                .queryParam("locale", locale)
                .queryParam("types[]", "city"));
        List<TravelpayoutsPlace> body = get(uri, new ParameterizedTypeReference<List<TravelpayoutsPlace>>() {},
                "autocomplétion");
        return body == null ? List.of() : body;
    }

    /** Code IATA → nom de compagnie, dans la langue demandée ({@code fr} ou {@code en}). */
    public Map<String, String> airlineNames(String locale) {
        URI uri = uri(apiBaseUrl, b -> b.path("/data/" + locale + "/airlines.json"));
        List<Airline> body = get(uri, new ParameterizedTypeReference<List<Airline>>() {}, "compagnies");
        Map<String, String> names = new HashMap<>();
        if (body != null) {
            for (Airline a : body) {
                if (a.code() != null && a.name() != null && !a.name().isBlank()) {
                    names.put(a.code().toUpperCase(Locale.ROOT), a.name());
                }
            }
        }
        return names;
    }

    private interface UriCustomizer {
        UriComponentsBuilder apply(UriComponentsBuilder builder);
    }

    /**
     * Construit puis encode l'URI sans expansion de gabarit : {@code UriBuilder.build()} lirait
     * un « {x} » saisi dans le terme d'autocomplétion comme une variable et lèverait une
     * exception (500) au lieu d'envoyer le texte tel quel.
     */
    private static URI uri(String base, UriCustomizer customizer) {
        return customizer.apply(UriComponentsBuilder.fromUriString(base)).build().encode().toUri();
    }

    private <T> T get(URI uri, Class<T> type, String what) {
        try {
            return restClient.get().uri(uri).retrieve().body(type);
        } catch (RuntimeException e) {
            // Jamais l'URI dans le log : elle porte le token.
            log.warn("Travelpayouts injoignable ({}) : {}", what, e.getClass().getSimpleName());
            throw new FlightProviderException("Travelpayouts injoignable (" + what + ")", e);
        }
    }

    private <T> T get(URI uri, ParameterizedTypeReference<T> type, String what) {
        try {
            return restClient.get().uri(uri).retrieve().body(type);
        } catch (RuntimeException e) {
            log.warn("Travelpayouts injoignable ({}) : {}", what, e.getClass().getSimpleName());
            throw new FlightProviderException("Travelpayouts injoignable (" + what + ")", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PricesResponse(boolean success, List<TravelpayoutsPrice> data, String currency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Airline(String code, String name) {
    }
}
