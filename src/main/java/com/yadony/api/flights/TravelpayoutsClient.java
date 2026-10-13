package com.yadony.api.flights;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
 * <p>Lectures seulement : les prix en cache d'Aviasales, l'autocomplétion des
 * villes, la liste des compagnies et les fuseaux des aéroports et des villes. Aucune vente ne passe par ici : l'utilisateur
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
     * @param limit      taille de page (Travelpayouts plafonne à 1000)
     * @param page       numéro de page, à partir de 1 : la page 2 reprend après les {@code limit}
     *                   offres les moins chères de la page 1
     * @throws FlightProviderException si l'API est injoignable ou répond une erreur
     */
    public List<TravelpayoutsPrice> pricesForDates(String origin, String destination,
                                                   FlightDate departure, FlightDate returnDate,
                                                   boolean directOnly, String currency, int limit, int page) {
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
                    .queryParam("page", page)
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

    /**
     * Code IATA → nom de compagnie, dans la langue demandée ({@code fr} ou {@code en}).
     *
     * <p>Dans la liste française, {@code name} vaut {@code null} pour la plupart des compagnies
     * (909 sur 1159 en octobre 2026, dont Corsair {@code SS} et Mauritania Airlines {@code L6}) :
     * le nom n'existe alors que dans {@code name_translations.en}. On prend donc {@code name},
     * puis la traduction dans la langue demandée, puis l'anglais, puis n'importe quelle traduction.
     */
    public Map<String, String> airlineNames(String locale) {
        URI uri = uri(apiBaseUrl, b -> b.path("/data/" + locale + "/airlines.json"));
        List<Airline> body = get(uri, new ParameterizedTypeReference<List<Airline>>() {}, "compagnies");
        Map<String, String> names = new HashMap<>();
        if (body != null) {
            for (Airline a : body) {
                if (a == null || a.code() == null || a.code().isBlank()) continue;
                String name = a.displayName(locale);
                if (name != null) names.put(a.code().trim().toUpperCase(Locale.ROOT), name);
            }
        }
        return names;
    }

    /**
     * Code IATA → fuseau horaire IANA ({@code ABJ} → {@code Africa/Abidjan}), depuis la liste
     * des aéroports ({@code kind = "airports"}) ou des villes ({@code kind = "cities"}).
     * Les lignes sans fuseau sont ignorées.
     */
    public Map<String, String> timeZones(String kind) {
        if (!"airports".equals(kind) && !"cities".equals(kind)) {
            throw new IllegalArgumentException("Liste inconnue : " + kind);
        }
        URI uri = uri(apiBaseUrl, b -> b.path("/data/en/" + kind + ".json"));
        List<Place> body = get(uri, new ParameterizedTypeReference<List<Place>>() {}, "fuseaux " + kind);
        Map<String, String> zones = new HashMap<>();
        if (body != null) {
            for (Place p : body) {
                if (p.code() != null && p.timeZone() != null && !p.timeZone().isBlank()) {
                    zones.put(p.code().toUpperCase(Locale.ROOT), p.timeZone().trim());
                }
            }
        }
        return zones;
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
    public record Place(String code, @JsonProperty("time_zone") String timeZone) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Airline(String code, String name,
                          @JsonProperty("name_translations") Map<String, String> nameTranslations) {

        /** {@code name}, sinon la traduction {@code locale}, sinon l'anglais, sinon la première non vide. */
        String displayName(String locale) {
            if (!blank(name)) return name.trim();
            if (nameTranslations == null || nameTranslations.isEmpty()) return null;
            for (String lang : new String[]{locale, "en"}) {
                String t = lang == null ? null : nameTranslations.get(lang);
                if (!blank(t)) return t.trim();
            }
            return nameTranslations.values().stream().filter(t -> !blank(t)).map(String::trim)
                    .sorted().findFirst().orElse(null);
        }

        private static boolean blank(String s) {
            return s == null || s.isBlank();
        }
    }
}
