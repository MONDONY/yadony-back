package com.yadony.api.address;

import com.yadony.api.address.dto.AutocompleteSuggestion;
import com.yadony.api.address.dto.PlaceDetailsResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class GoogleAddressService {

    private static final Logger log = LoggerFactory.getLogger(GoogleAddressService.class);

    // Places API (New) — POST autocomplete, GET place details (with field mask header)
    private static final String AUTOCOMPLETE_URL =
        "https://places.googleapis.com/v1/places:autocomplete";
    private static final String DETAILS_URL_TEMPLATE =
        "https://places.googleapis.com/v1/places/{placeId}";
    private static final String DETAILS_FIELD_MASK = "id,formattedAddress,location,addressComponents";

    // Geocoding API (legacy URL but distinct API — still the standard for reverse)
    private static final String GEOCODE_URL =
        "https://maps.googleapis.com/maps/api/geocode/json";

    private final GooglePlacesProperties props;
    private final RestTemplate restTemplate;
    private final Cache<String, AtomicLong> dailyQuotaCache;

    public GoogleAddressService(GooglePlacesProperties props,
                                @Qualifier("placesRestTemplate") RestTemplate restTemplate,
                                @Qualifier("addressDailyQuotaCache") Cache<String, AtomicLong> dailyQuotaCache) {
        this.props = props;
        this.restTemplate = restTemplate;
        this.dailyQuotaCache = dailyQuotaCache;
    }

    public List<AutocompleteSuggestion> autocomplete(String query, String sessionToken,
                                                      Double lat, Double lng) {
        checkAndIncrementDailyQuota("autocomplete", props.dailyQuotaAutocomplete());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", query);
        body.put("sessionToken", sessionToken);
        body.put("languageCode", "fr");

        List<String> regionCodes = buildRegionCodes();
        if (!regionCodes.isEmpty()) {
            body.put("includedRegionCodes", regionCodes);
        }
        // Biais de proximité désactivé par défaut : un cercle de 50 km sature les
        // 5 suggestions Google de résultats locaux et empêche une ville lointaine
        // (ex. Toronto tapé depuis la France) de remonter. Réactivable via config.
        if (props.locationBiasEnabled() && lat != null && lng != null) {
            body.put("locationBias", Map.of(
                "circle", Map.of(
                    "center", Map.of("latitude", lat, "longitude", lng),
                    "radius", 50_000.0
                )
            ));
        }

        Map<String, Object> response = postPlaces(AUTOCOMPLETE_URL, body, "autocomplete");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suggestions =
            (List<Map<String, Object>>) response.get("suggestions");
        if (suggestions == null || suggestions.isEmpty()) return List.of();

        return suggestions.stream()
            .map(this::extractSuggestion)
            .filter(s -> s != null && s.placeId() != null)
            .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private AutocompleteSuggestion extractSuggestion(Map<String, Object> suggestion) {
        Map<String, Object> placePrediction =
            (Map<String, Object>) suggestion.get("placePrediction");
        if (placePrediction == null) return null;

        String placeId = (String) placePrediction.get("placeId");
        Map<String, Object> structuredFormat =
            (Map<String, Object>) placePrediction.get("structuredFormat");

        String mainText = "";
        String secondaryText = "";
        if (structuredFormat != null) {
            Map<String, Object> mainTextMap =
                (Map<String, Object>) structuredFormat.get("mainText");
            if (mainTextMap != null && mainTextMap.get("text") != null) {
                mainText = (String) mainTextMap.get("text");
            }
            Map<String, Object> secondaryTextMap =
                (Map<String, Object>) structuredFormat.get("secondaryText");
            if (secondaryTextMap != null && secondaryTextMap.get("text") != null) {
                secondaryText = (String) secondaryTextMap.get("text");
            }
        }
        return new AutocompleteSuggestion(placeId, mainText, secondaryText);
    }

    public PlaceDetailsResponse details(String placeId, String sessionToken) {
        checkAndIncrementDailyQuota("details", props.dailyQuotaDetails());

        String url = UriComponentsBuilder.fromHttpUrl(DETAILS_URL_TEMPLATE)
            .queryParam("languageCode", "fr")
            .queryParam("sessionToken", sessionToken)
            .buildAndExpand(placeId)
            .toUriString();

        Map<String, Object> response = getPlaceDetails(url, "details");
        return parsePlaceDetailsNew(response);
    }

    public Optional<PlaceDetailsResponse> reverse(double lat, double lng) {
        checkAndIncrementDailyQuota("reverse", props.dailyQuotaReverse());

        String url = UriComponentsBuilder.fromHttpUrl(GEOCODE_URL)
            .queryParam("latlng", lat + "," + lng)
            .queryParam("language", "fr")
            .queryParam("key", props.apiKey())
            .build().toUriString();

        Map<String, Object> response = fetchGeocode(url);
        if ("ZERO_RESULTS".equals(response.get("status"))) return Optional.empty();
        checkGeocodeStatus((String) response.get("status"), "reverse");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results =
            (List<Map<String, Object>>) response.get("results");
        if (results == null || results.isEmpty()) return Optional.empty();
        return Optional.of(parseGeocodeResult(results.get(0)));
    }

    // Package-private for testing
    String buildComponentsParam() {
        if (props.allowedCountries() == null || props.allowedCountries().isBlank()) return "";
        return Arrays.stream(props.allowedCountries().split(","))
            .map(c -> "country:" + c.trim())
            .collect(Collectors.joining("|"));
    }

    // Google Places (New) autorise au plus 15 valeurs included_region_codes.
    // Au-delà, l'API renvoie 400 INVALID_ARGUMENT — on plafonne donc à 15.
    private static final int MAX_REGION_CODES = 15;

    private List<String> buildRegionCodes() {
        if (props.allowedCountries() == null || props.allowedCountries().isBlank()) return List.of();
        return Arrays.stream(props.allowedCountries().split(","))
            .map(String::trim)
            .filter(c -> !c.isEmpty())
            .map(String::toUpperCase)
            .limit(MAX_REGION_CODES)
            .collect(Collectors.toList());
    }

    private void checkAndIncrementDailyQuota(String type, int limit) {
        if (!props.blockWhenQuotaExceeded()) return;
        String key = "places:quota:" + type + ":" + LocalDate.now();
        long count = dailyQuotaCache.get(key, k -> new AtomicLong(0)).incrementAndGet();
        if (count == (long) (limit * 0.5))
            log.warn("[Places quota] {} : 50% atteint ({}/{})", type, count, limit);
        else if (count == (long) (limit * 0.8))
            log.warn("[Places quota] {} : 80% atteint ({}/{})", type, count, limit);
        else if (count == (long) (limit * 0.95))
            log.error("[Places quota] {} : 95% atteint — blocage imminent ({}/{})", type, count, limit);
        if (count > limit) {
            log.error("[Places quota] {} : quota journalier EPUISE ({}/{})", type, count, limit);
            throw new YadonyBusinessException(
                HttpStatus.TOO_MANY_REQUESTS,
                "daily-quota-exceeded",
                "Service temporairement indisponible",
                "Le service de recherche d'adresse est suspendu jusqu'a demain. Utilisez le bouton GPS pour indiquer votre position."
            );
        }
    }

    @SuppressWarnings("unchecked")
    private PlaceDetailsResponse parsePlaceDetailsNew(Map<String, Object> result) {
        if (result == null) {
            log.error("Google Places (New) details: empty body");
            throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
                "google-invalid-response", "Bad Gateway", "Réponse Google incomplète");
        }
        String formattedAddress = (String) result.get("formattedAddress");
        Map<String, Object> location = (Map<String, Object>) result.get("location");
        if (formattedAddress == null || location == null
                || location.get("latitude") == null || location.get("longitude") == null) {
            log.error("Google Places (New) details: missing formattedAddress or location");
            throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
                "google-invalid-response", "Bad Gateway", "Réponse Google incomplète");
        }

        List<Map<String, Object>> components =
            (List<Map<String, Object>>) result.get("addressComponents");
        String streetNumber = componentLongText(components, "street_number");
        String route = componentLongText(components, "route");
        String street = joinStreet(streetNumber, route);
        String city = firstNonNull(
            componentLongText(components, "locality"),
            componentLongText(components, "postal_town"),
            componentLongText(components, "administrative_area_level_2"));
        String postalCode = componentLongText(components, "postal_code");
        String country = componentShortText(components, "country");

        return new PlaceDetailsResponse(
            formattedAddress,
            ((Number) location.get("latitude")).doubleValue(),
            ((Number) location.get("longitude")).doubleValue(),
            street, city, postalCode, country
        );
    }

    private static String componentLongText(List<Map<String, Object>> components, String type) {
        return componentText(components, type, "longText");
    }

    private static String componentShortText(List<Map<String, Object>> components, String type) {
        return componentText(components, type, "shortText");
    }

    @SuppressWarnings("unchecked")
    private static String componentText(List<Map<String, Object>> components, String type, String field) {
        if (components == null) return null;
        for (Map<String, Object> c : components) {
            Object typesObj = c.get("types");
            if (typesObj instanceof List<?> types && types.contains(type)) {
                Object v = c.get(field);
                return v == null ? null : v.toString();
            }
        }
        return null;
    }

    private static String joinStreet(String number, String route) {
        if (route == null || route.isBlank()) return number == null || number.isBlank() ? null : number;
        if (number == null || number.isBlank()) return route;
        return (number + " " + route).trim();
    }

    private static String firstNonNull(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    @SuppressWarnings("unchecked")
    private PlaceDetailsResponse parseGeocodeResult(Map<String, Object> result) {
        if (result == null) {
            log.error("Geocoding API result is null — response malformed");
            throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
                "google-invalid-response", "Bad Gateway", "Réponse Google incomplète");
        }
        Map<String, Object> geometry = (Map<String, Object>) result.get("geometry");
        if (geometry == null) {
            log.error("Geocoding API result missing geometry field");
            throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
                "google-invalid-response", "Bad Gateway", "Réponse Google incomplète");
        }
        Map<String, Object> location = (Map<String, Object>) geometry.get("location");
        if (location == null) {
            log.error("Geocoding API result missing geometry.location field");
            throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
                "google-invalid-response", "Bad Gateway", "Réponse Google incomplète");
        }
        return new PlaceDetailsResponse(
            (String) result.get("formatted_address"),
            ((Number) location.get("lat")).doubleValue(),
            ((Number) location.get("lng")).doubleValue(),
            // reverse geocoding: composants structurés non requis (spec §4.2)
            null, null, null, null
        );
    }

    /** POST to Places API (New) with X-Goog-Api-Key + JSON body. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> postPlaces(String url, Map<String, Object> body, String operation) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Goog-Api-Key", props.apiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, request, Map.class);
            Map<String, Object> result = response.getBody();
            if (result == null) {
                log.error("Google Places (New) {} returned empty body", operation);
                throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
                    "google-empty-response", "Bad Gateway", "Réponse Google vide");
            }
            return result;
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            throw mapPlacesHttpError(e.getStatusCode(), operation, e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            log.error("Google Places (New) {} timeout: {}", operation, e.getMessage());
            throw new YadonyBusinessException(HttpStatus.GATEWAY_TIMEOUT, "google-timeout",
                "Gateway Timeout", "Google API timeout");
        }
    }

    /** GET Place Details (New) with X-Goog-Api-Key + X-Goog-FieldMask. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> getPlaceDetails(String url, String operation) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Goog-Api-Key", props.apiKey());
        headers.set("X-Goog-FieldMask", DETAILS_FIELD_MASK);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, request, Map.class);
            Map<String, Object> result = response.getBody();
            if (result == null) {
                log.error("Google Places (New) {} returned empty body", operation);
                throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
                    "google-empty-response", "Bad Gateway", "Réponse Google vide");
            }
            return result;
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            throw mapPlacesHttpError(e.getStatusCode(), operation, e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            log.error("Google Places (New) {} timeout: {}", operation, e.getMessage());
            throw new YadonyBusinessException(HttpStatus.GATEWAY_TIMEOUT, "google-timeout",
                "Gateway Timeout", "Google API timeout");
        }
    }

    private YadonyBusinessException mapPlacesHttpError(HttpStatusCode status, String operation, String body) {
        int code = status.value();
        log.error("Google Places (New) {} HTTP {} : {}", operation, code, body);
        if (code == 400) {
            return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "google-invalid-request", "Invalid Request", "Requête Google invalide");
        }
        if (code == 401 || code == 403) {
            return new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
                "google-request-denied", "Bad Gateway", "Accès Google Places refusé");
        }
        if (code == 404) {
            return new YadonyBusinessException(HttpStatus.NOT_FOUND,
                "google-not-found", "Not Found", "Lieu introuvable");
        }
        if (code == 429) {
            return new YadonyBusinessException(HttpStatus.TOO_MANY_REQUESTS,
                "google-quota-exceeded", "Too Many Requests", "Quota Google dépassé");
        }
        return new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
            "google-unexpected-status", "Bad Gateway",
            "Statut Google inattendu: " + code);
    }

    /** GET Geocoding API (legacy URL — distinct API, still standard for reverse geocoding). */
    private Map<String, Object> fetchGeocode(String url) {
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Map<String, Object> body = restTemplate.getForObject(url, Map.class);
            if (body == null) {
                log.error("Geocoding API returned empty body");
                throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
                    "google-empty-response", "Bad Gateway", "Réponse Google vide");
            }
            return body;
        } catch (ResourceAccessException e) {
            log.error("Geocoding API timeout: {}", e.getMessage());
            throw new YadonyBusinessException(HttpStatus.GATEWAY_TIMEOUT, "google-timeout",
                "Gateway Timeout", "Google API timeout");
        }
    }

    private void checkGeocodeStatus(String status, String operation) {
        switch (status == null ? "" : status) {
            case "OK", "ZERO_RESULTS" -> { /* normal */ }
            case "INVALID_REQUEST" -> {
                log.warn("Geocoding INVALID_REQUEST on {}", operation);
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "google-invalid-request", "Invalid Request", "Requête Google invalide");
            }
            case "REQUEST_DENIED" -> {
                log.error("Geocoding REQUEST_DENIED on {} — check API key restrictions", operation);
                throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
                    "google-request-denied", "Bad Gateway", "Accès Google Geocoding refusé");
            }
            case "OVER_QUERY_LIMIT" -> {
                log.error("Geocoding OVER_QUERY_LIMIT on {} — quota exceeded", operation);
                throw new YadonyBusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "google-quota-exceeded", "Too Many Requests", "Quota Google dépassé");
            }
            default -> {
                log.error("Unexpected Geocoding status {} on {} — treating as bad gateway", status, operation);
                throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
                    "google-unexpected-status", "Bad Gateway",
                    "Statut Google inattendu: " + status);
            }
        }
    }
}
