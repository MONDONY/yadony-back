package com.yadony.api.e2e.hooks;

import com.yadony.api.common.StorageService;
import io.cucumber.java.Before;
import io.restassured.RestAssured;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Global hooks run before every scenario.
 */
public class CucumberHooks {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StorageService storageService;

    @Autowired
    @Qualifier("placesRestTemplate")
    private RestTemplate placesRestTemplate;

    @Before(order = 0)
    public void configureRestAssured() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;
        RestAssured.basePath = "/api/v1";
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    @Before(order = 1)
    public void cleanDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    tracking_events,
                    rematch_suggestions,
                    cancellations,
                    payments,
                    bids,
                    announcements,
                    audit_log,
                    stripe_event_inbox,
                    kyc_schema.kyc_verifications,
                    user_roles,
                    users
                CASCADE
                """);
    }

    @Before(order = 2)
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void resetMocks() {
        Mockito.reset(storageService);
        Mockito.when(storageService.generatePresignedUrl(Mockito.anyString(), Mockito.any()))
               .thenReturn("https://fake-s3.yadony.test/photo.jpg");
        Mockito.when(storageService.storeImage(Mockito.any(), Mockito.any()))
               .thenReturn("tracking/test/fake-key.jpg");

        // Google Places / Geocoding stubs — POST = autocomplete, GET = details, getForObject = reverse.
        Mockito.reset(placesRestTemplate);
        Map<String, Object> autocomplete = Map.of("suggestions", List.of(Map.of(
                "placePrediction", Map.of(
                        "placeId", "ChIJtest123",
                        "structuredFormat", Map.of(
                                "mainText", Map.of("text", "Dakar"),
                                "secondaryText", Map.of("text", "Sénégal"))))));
        Map<String, Object> details = Map.of(
                "formattedAddress", "Avenue Léopold Sédar Senghor, Dakar, Sénégal",
                "location", Map.of("latitude", 14.6928, "longitude", -17.4467),
                "addressComponents", List.of(
                        Map.of("types", List.of("route"), "longText", "Avenue Léopold Sédar Senghor", "shortText", "Av. LSS"),
                        Map.of("types", List.of("locality"), "longText", "Dakar", "shortText", "Dakar"),
                        Map.of("types", List.of("postal_code"), "longText", "10000", "shortText", "10000"),
                        Map.of("types", List.of("country"), "longText", "Senegal", "shortText", "SN")));
        Map<String, Object> geocode = Map.of("status", "OK", "results", List.of(Map.of(
                "formatted_address", "Dakar, Sénégal",
                "geometry", Map.of("location", Map.of("lat", 14.6928, "lng", -17.4467)))));
        Mockito.when(placesRestTemplate.exchange(Mockito.anyString(), Mockito.eq(HttpMethod.POST),
                        Mockito.any(), Mockito.eq(Map.class)))
                .thenReturn((ResponseEntity) ResponseEntity.ok(autocomplete));
        Mockito.when(placesRestTemplate.exchange(Mockito.anyString(), Mockito.eq(HttpMethod.GET),
                        Mockito.any(), Mockito.eq(Map.class)))
                .thenReturn((ResponseEntity) ResponseEntity.ok(details));
        Mockito.when(placesRestTemplate.getForObject(Mockito.anyString(), Mockito.eq(Map.class)))
                .thenReturn(geocode);
    }
}
