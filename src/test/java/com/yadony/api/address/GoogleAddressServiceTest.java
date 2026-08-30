package com.yadony.api.address;

import com.yadony.api.address.dto.AutocompleteSuggestion;
import com.yadony.api.address.dto.PlaceDetailsResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;

@ExtendWith(MockitoExtension.class)
class GoogleAddressServiceTest {

    @Mock
    RestTemplate restTemplate;

    Cache<String, AtomicLong> dailyQuotaCache;
    GoogleAddressService service;

    private static GooglePlacesProperties defaultProps() {
        return new GooglePlacesProperties("test-key", "FR,SN", 100, 5000, 1000, 500, true, false);
    }

    @BeforeEach
    void setUp() {
        dailyQuotaCache = Caffeine.newBuilder().maximumSize(100).build();
        service = new GoogleAddressService(defaultProps(), restTemplate, dailyQuotaCache);
    }

    // ── Autocomplete (Places API New — POST) ─────────────────────────────────

    @Test
    void autocomplete_returnsListOfSuggestions() {
        Map<String, Object> placePrediction = Map.of(
            "placeId", "ChIJi...",
            "structuredFormat", Map.of(
                "mainText", Map.of("text", "12 rue Victor Hugo"),
                "secondaryText", Map.of("text", "Lyon, France")
            )
        );
        Map<String, Object> body = Map.of(
            "suggestions", List.of(Map.of("placePrediction", placePrediction))
        );
        when(restTemplate.exchange(contains("places:autocomplete"),
                eq(HttpMethod.POST), any(), eq(Map.class)))
            .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        List<AutocompleteSuggestion> results =
            service.autocomplete("12 rue Victor", "token-abc", null, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).placeId()).isEqualTo("ChIJi...");
        assertThat(results.get(0).mainText()).isEqualTo("12 rue Victor Hugo");
        assertThat(results.get(0).secondaryText()).isEqualTo("Lyon, France");
    }

    @Test
    void autocomplete_zeroResults_returnsEmptyList() {
        when(restTemplate.exchange(contains("places:autocomplete"),
                eq(HttpMethod.POST), any(), eq(Map.class)))
            .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.OK)); // no "suggestions" key

        List<AutocompleteSuggestion> results =
            service.autocomplete("xyzzy", "token-abc", null, null);

        assertThat(results).isEmpty();
    }

    @Test
    void autocomplete_emptySuggestionsArray_returnsEmptyList() {
        when(restTemplate.exchange(contains("places:autocomplete"),
                eq(HttpMethod.POST), any(), eq(Map.class)))
            .thenReturn(new ResponseEntity<>(Map.of("suggestions", List.of()), HttpStatus.OK));

        assertThat(service.autocomplete("xyzzy", "tok", null, null)).isEmpty();
    }

    @Test
    void autocomplete_googleHttp403_throws502() {
        when(restTemplate.exchange(contains("places:autocomplete"),
                eq(HttpMethod.POST), any(), eq(Map.class)))
            .thenThrow(HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", new HttpHeaders(), new byte[0], null));

        assertThatThrownBy(() -> service.autocomplete("Paris", "tok", null, null))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(ex -> {
                YadonyBusinessException e = (YadonyBusinessException) ex;
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(e.getErrorCode()).isEqualTo("google-request-denied");
            });
    }

    @Test
    void autocomplete_googleHttp400_throws422() {
        when(restTemplate.exchange(contains("places:autocomplete"),
                eq(HttpMethod.POST), any(), eq(Map.class)))
            .thenThrow(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(), new byte[0], null));

        assertThatThrownBy(() -> service.autocomplete("Paris", "tok", null, null))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(ex -> {
                YadonyBusinessException e = (YadonyBusinessException) ex;
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.getErrorCode()).isEqualTo("google-invalid-request");
            });
    }

    @Test
    void autocomplete_googleHttp429_throws429() {
        when(restTemplate.exchange(contains("places:autocomplete"),
                eq(HttpMethod.POST), any(), eq(Map.class)))
            .thenThrow(HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many", new HttpHeaders(), new byte[0], null));

        assertThatThrownBy(() -> service.autocomplete("Paris", "tok", null, null))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(ex -> {
                YadonyBusinessException e = (YadonyBusinessException) ex;
                assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(e.getErrorCode()).isEqualTo("google-quota-exceeded");
            });
    }

    @Test
    void autocomplete_googleHttp500_throws502() {
        when(restTemplate.exchange(contains("places:autocomplete"),
                eq(HttpMethod.POST), any(), eq(Map.class)))
            .thenThrow(HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "Server Error", new HttpHeaders(), new byte[0], null));

        assertThatThrownBy(() -> service.autocomplete("Paris", "tok", null, null))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(ex -> {
                YadonyBusinessException e = (YadonyBusinessException) ex;
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(e.getErrorCode()).isEqualTo("google-unexpected-status");
            });
    }

    @Test
    void autocomplete_timeout_throws504() {
        when(restTemplate.exchange(contains("places:autocomplete"),
                eq(HttpMethod.POST), any(), eq(Map.class)))
            .thenThrow(new ResourceAccessException("Connection timed out"));

        assertThatThrownBy(() -> service.autocomplete("Paris", "tok", null, null))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(ex -> {
                YadonyBusinessException e = (YadonyBusinessException) ex;
                assertThat(e.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                assertThat(e.getErrorCode()).isEqualTo("google-timeout");
            });
    }

    @Test
    void autocomplete_emptyBody_throws502() {
        when(restTemplate.exchange(contains("places:autocomplete"),
                eq(HttpMethod.POST), any(), eq(Map.class)))
            .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        assertThatThrownBy(() -> service.autocomplete("Paris", "tok", null, null))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(ex -> {
                YadonyBusinessException e = (YadonyBusinessException) ex;
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(e.getErrorCode()).isEqualTo("google-empty-response");
            });
    }

    // ── Place Details (Places API New — GET with field mask header) ──────────

    @Test
    void details_returnsParsedAddress() {
        Map<String, Object> body = Map.of(
            "id", "ChIJi...",
            "formattedAddress", "12 Rue Victor Hugo, 69002 Lyon, France",
            "location", Map.of("latitude", 45.7484, "longitude", 4.8467)
        );
        when(restTemplate.exchange(contains("/v1/places/ChIJi"),
                eq(HttpMethod.GET), any(), eq(Map.class)))
            .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        PlaceDetailsResponse response = service.details("ChIJi...", "token-abc");

        assertThat(response.label()).isEqualTo("12 Rue Victor Hugo, 69002 Lyon, France");
        assertThat(response.lat()).isEqualTo(45.7484);
        assertThat(response.lng()).isEqualTo(4.8467);
    }

    @Test
    void details_notFound_throws404() {
        when(restTemplate.exchange(contains("/v1/places/"),
                eq(HttpMethod.GET), any(), eq(Map.class)))
            .thenThrow(HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found", new HttpHeaders(), new byte[0], null));

        assertThatThrownBy(() -> service.details("invalid-id", "tok"))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(ex -> {
                YadonyBusinessException e = (YadonyBusinessException) ex;
                assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(e.getErrorCode()).isEqualTo("google-not-found");
            });
    }

    @Test
    void details_emptyBody_throws502() {
        when(restTemplate.exchange(contains("/v1/places/"),
                eq(HttpMethod.GET), any(), eq(Map.class)))
            .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        assertThatThrownBy(() -> service.details("ChIJi...", "tok"))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(ex -> {
                YadonyBusinessException e = (YadonyBusinessException) ex;
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(e.getErrorCode()).isEqualTo("google-empty-response");
            });
    }

    @Test
    void details_missingLocation_throws502() {
        when(restTemplate.exchange(contains("/v1/places/"),
                eq(HttpMethod.GET), any(), eq(Map.class)))
            .thenReturn(new ResponseEntity<>(
                Map.of("id", "x", "formattedAddress", "Paris"), HttpStatus.OK));

        assertThatThrownBy(() -> service.details("ChIJi...", "tok"))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(ex -> {
                YadonyBusinessException e = (YadonyBusinessException) ex;
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(e.getErrorCode()).isEqualTo("google-invalid-response");
            });
    }

    @Test
    void details_missingFormattedAddress_throws502() {
        when(restTemplate.exchange(contains("/v1/places/"),
                eq(HttpMethod.GET), any(), eq(Map.class)))
            .thenReturn(new ResponseEntity<>(
                Map.of("id", "x", "location", Map.of("latitude", 1.0, "longitude", 2.0)),
                HttpStatus.OK));

        assertThatThrownBy(() -> service.details("ChIJi...", "tok"))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(ex -> {
                YadonyBusinessException e = (YadonyBusinessException) ex;
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(e.getErrorCode()).isEqualTo("google-invalid-response");
            });
    }

    // ── Reverse (Geocoding API — unchanged, GET) ─────────────────────────────

    @Test
    void reverse_returnsAddress() {
        Map<String, Object> locationMap = Map.of("lat", 14.693, "lng", -17.447);
        Map<String, Object> resultEntry = Map.of(
            "formatted_address", "Plateau, Dakar, Sénégal",
            "geometry", Map.of("location", locationMap)
        );
        when(restTemplate.getForObject(contains("geocode"), eq(Map.class)))
            .thenReturn(Map.of("status", "OK", "results", List.of(resultEntry)));

        Optional<PlaceDetailsResponse> result = service.reverse(14.693, -17.447);

        assertThat(result).isPresent();
        assertThat(result.get().label()).isEqualTo("Plateau, Dakar, Sénégal");
        assertThat(result.get().lat()).isEqualTo(14.693);
        assertThat(result.get().lng()).isEqualTo(-17.447);
    }

    @Test
    void reverse_noResult_returnsEmpty() {
        when(restTemplate.getForObject(contains("geocode"), eq(Map.class)))
            .thenReturn(Map.of("status", "ZERO_RESULTS", "results", List.of()));

        Optional<PlaceDetailsResponse> result = service.reverse(0.0, 0.0);

        assertThat(result).isEmpty();
    }

    @Test
    void reverse_emptyResults_returnsEmpty() {
        when(restTemplate.getForObject(contains("geocode"), eq(Map.class)))
            .thenReturn(Map.of("status", "OK", "results", List.of()));

        Optional<PlaceDetailsResponse> result = service.reverse(48.8566, 2.3522);
        assertThat(result).isEmpty();
    }

    @Test
    void reverse_requestDenied_throws502() {
        when(restTemplate.getForObject(contains("geocode"), eq(Map.class)))
            .thenReturn(Map.of("status", "REQUEST_DENIED"));

        assertThatThrownBy(() -> service.reverse(0.0, 0.0))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(ex -> {
                YadonyBusinessException e = (YadonyBusinessException) ex;
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(e.getErrorCode()).isEqualTo("google-request-denied");
            });
    }

    @Test
    void reverse_overQueryLimit_throws429() {
        when(restTemplate.getForObject(contains("geocode"), eq(Map.class)))
            .thenReturn(Map.of("status", "OVER_QUERY_LIMIT"));

        assertThatThrownBy(() -> service.reverse(0.0, 0.0))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(ex -> {
                YadonyBusinessException e = (YadonyBusinessException) ex;
                assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(e.getErrorCode()).isEqualTo("google-quota-exceeded");
            });
    }

    @Test
    void reverse_missingGeometry_throws502() {
        when(restTemplate.getForObject(contains("geocode"), eq(Map.class)))
            .thenReturn(Map.of("status", "OK",
                "results", List.of(Map.of("formatted_address", "Paris"))));

        assertThatThrownBy(() -> service.reverse(48.8566, 2.3522))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(ex -> {
                YadonyBusinessException e = (YadonyBusinessException) ex;
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(e.getErrorCode()).isEqualTo("google-invalid-response");
            });
    }

    @Test
    void reverse_invalidRequest_throws422() {
        when(restTemplate.getForObject(contains("geocode"), eq(Map.class)))
            .thenReturn(Map.of("status", "INVALID_REQUEST"));

        assertThatThrownBy(() -> service.reverse(0.0, 0.0))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(ex -> {
                YadonyBusinessException e = (YadonyBusinessException) ex;
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.getErrorCode()).isEqualTo("google-invalid-request");
            });
    }

    @Test
    void details_extractsStructuredComponents() {
        Map<String, Object> body = Map.of(
            "id", "ChIJi...",
            "formattedAddress", "12 Rue Victor Hugo, 69002 Lyon, France",
            "location", Map.of("latitude", 45.7484, "longitude", 4.8467),
            "addressComponents", List.of(
                Map.of("longText", "12", "shortText", "12", "types", List.of("street_number")),
                Map.of("longText", "Rue Victor Hugo", "shortText", "Rue Victor Hugo", "types", List.of("route")),
                Map.of("longText", "Lyon", "shortText", "Lyon", "types", List.of("locality", "political")),
                Map.of("longText", "69002", "shortText", "69002", "types", List.of("postal_code")),
                Map.of("longText", "France", "shortText", "FR", "types", List.of("country", "political"))
            )
        );
        when(restTemplate.exchange(contains("/v1/places/ChIJi"),
                eq(HttpMethod.GET), any(), eq(Map.class)))
            .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        PlaceDetailsResponse r = service.details("ChIJi...", "token-abc");

        assertThat(r.street()).isEqualTo("12 Rue Victor Hugo");
        assertThat(r.city()).isEqualTo("Lyon");
        assertThat(r.postalCode()).isEqualTo("69002");
        assertThat(r.country()).isEqualTo("FR");
    }

    @Test
    void details_missingComponents_returnsNullParts() {
        Map<String, Object> body = Map.of(
            "id", "ChIJx",
            "formattedAddress", "Dakar, Sénégal",
            "location", Map.of("latitude", 14.693, "longitude", -17.447),
            "addressComponents", List.of(
                Map.of("longText", "Dakar", "shortText", "Dakar", "types", List.of("locality", "political")),
                Map.of("longText", "Sénégal", "shortText", "SN", "types", List.of("country", "political"))
            )
        );
        when(restTemplate.exchange(contains("/v1/places/ChIJx"),
                eq(HttpMethod.GET), any(), eq(Map.class)))
            .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        PlaceDetailsResponse r = service.details("ChIJx", "tok");

        assertThat(r.city()).isEqualTo("Dakar");
        assertThat(r.country()).isEqualTo("SN");
        assertThat(r.street()).isNull();
        assertThat(r.postalCode()).isNull();
    }

    @Test
    void details_noAddressComponents_keepsBackwardCompat() {
        Map<String, Object> body = Map.of(
            "id", "ChIJi...",
            "formattedAddress", "12 Rue Victor Hugo, 69002 Lyon, France",
            "location", Map.of("latitude", 45.7484, "longitude", 4.8467)
        );
        when(restTemplate.exchange(contains("/v1/places/ChIJi"),
                eq(HttpMethod.GET), any(), eq(Map.class)))
            .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        PlaceDetailsResponse r = service.details("ChIJi...", "tok");

        assertThat(r.label()).isEqualTo("12 Rue Victor Hugo, 69002 Lyon, France");
        assertThat(r.street()).isNull();
        assertThat(r.city()).isNull();
        assertThat(r.postalCode()).isNull();
        assertThat(r.country()).isNull();
    }

    @Test
    void details_cityFallbackToPostalTown() {
        Map<String, Object> body = Map.of(
            "id", "ChIJp",
            "formattedAddress", "10 Downing St, London, UK",
            "location", Map.of("latitude", 51.5, "longitude", -0.12),
            "addressComponents", List.of(
                Map.of("longText", "London", "shortText", "London", "types", List.of("postal_town")),
                Map.of("longText", "United Kingdom", "shortText", "GB", "types", List.of("country"))
            )
        );
        when(restTemplate.exchange(contains("/v1/places/ChIJp"),
                eq(HttpMethod.GET), any(), eq(Map.class)))
            .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        assertThat(service.details("ChIJp", "tok").city()).isEqualTo("London");
    }

    // ── Biais de proximité (locationBias) ────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureAutocompleteBody(GoogleAddressService svc,
                                                        Double lat, Double lng) {
        when(restTemplate.exchange(contains("places:autocomplete"),
                eq(HttpMethod.POST), any(), eq(Map.class)))
            .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.OK));
        svc.autocomplete("Toronto", "tok", lat, lng);
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate).exchange(
            contains("places:autocomplete"), eq(HttpMethod.POST), captor.capture(), eq(Map.class));
        return (Map<String, Object>) captor.getValue().getBody();
    }

    @Test
    void autocomplete_biasDisabled_omitsLocationBiasEvenWithCoords() {
        GooglePlacesProperties props =
            new GooglePlacesProperties("key", "", 100, 5000, 1000, 500, true, false);
        GoogleAddressService svc = new GoogleAddressService(props, restTemplate, dailyQuotaCache);

        Map<String, Object> body = captureAutocompleteBody(svc, 48.85, 2.35);

        assertThat(body).doesNotContainKey("locationBias");
    }

    @Test
    void autocomplete_biasEnabled_includesLocationBiasWhenCoordsProvided() {
        GooglePlacesProperties props =
            new GooglePlacesProperties("key", "", 100, 5000, 1000, 500, true, true);
        GoogleAddressService svc = new GoogleAddressService(props, restTemplate, dailyQuotaCache);

        Map<String, Object> body = captureAutocompleteBody(svc, 48.85, 2.35);

        assertThat(body).containsKey("locationBias");
    }

    @Test
    void autocomplete_biasEnabled_noCoords_omitsLocationBias() {
        GooglePlacesProperties props =
            new GooglePlacesProperties("key", "", 100, 5000, 1000, 500, true, true);
        GoogleAddressService svc = new GoogleAddressService(props, restTemplate, dailyQuotaCache);

        Map<String, Object> body = captureAutocompleteBody(svc, null, null);

        assertThat(body).doesNotContainKey("locationBias");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @Test
    void buildComponentsParam_withCountries() {
        GooglePlacesProperties props = new GooglePlacesProperties("key", "FR,SN", 100, 5000, 1000, 500, true, false);
        GoogleAddressService svc = new GoogleAddressService(props, restTemplate, dailyQuotaCache);
        assertThat(svc.buildComponentsParam()).isEqualTo("country:FR|country:SN");
    }

    @Test
    void buildComponentsParam_emptyConfig_returnsEmpty() {
        GooglePlacesProperties props = new GooglePlacesProperties("key", "", 100, 5000, 1000, 500, true, false);
        GoogleAddressService svc = new GoogleAddressService(props, restTemplate, dailyQuotaCache);
        assertThat(svc.buildComponentsParam()).isEmpty();
    }

    // ── Daily quota ──────────────────────────────────────────────────────────

    @Test
    void autocomplete_dailyQuotaExceeded_throws429() {
        GooglePlacesProperties props = new GooglePlacesProperties("key", "", 100, 1, 1000, 500, true, false);
        GoogleAddressService svc = new GoogleAddressService(props, restTemplate, dailyQuotaCache);

        when(restTemplate.exchange(contains("places:autocomplete"),
                eq(HttpMethod.POST), any(), eq(Map.class)))
            .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.OK));
        svc.autocomplete("Paris", "tok", null, null); // uses quota slot 1

        assertThatThrownBy(() -> svc.autocomplete("Paris", "tok2", null, null))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(ex -> {
                YadonyBusinessException e = (YadonyBusinessException) ex;
                assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(e.getErrorCode()).isEqualTo("daily-quota-exceeded");
            });
    }

    @Test
    void autocomplete_quotaDisabled_noThrow() {
        GooglePlacesProperties props = new GooglePlacesProperties("key", "", 100, 0, 0, 0, false, false);
        GoogleAddressService svc = new GoogleAddressService(props, restTemplate, dailyQuotaCache);

        when(restTemplate.exchange(contains("places:autocomplete"),
                eq(HttpMethod.POST), any(), eq(Map.class)))
            .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.OK));

        // Should not throw even when quota is 0 because blockWhenQuotaExceeded=false
        assertThat(svc.autocomplete("Paris", "tok", null, null)).isEmpty();
    }
}
