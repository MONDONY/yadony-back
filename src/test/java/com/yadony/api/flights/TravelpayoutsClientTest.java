package com.yadony.api.flights;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TravelpayoutsClientTest {

    private MockRestServiceServer server;
    private TravelpayoutsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TravelpayoutsClient(builder.build(), "secret-token", "https://api.test", "https://auto.test");
    }

    @Test
    void pricesForDates_oneWay_sendsQueryAndParsesOffers() {
        server.expect(requestTo(startsWith("https://api.test/aviasales/v3/prices_for_dates")))
                .andExpect(queryParam("origin", "PAR"))
                .andExpect(queryParam("destination", "ABJ"))
                .andExpect(queryParam("currency", "eur"))
                .andExpect(queryParam("one_way", "true"))
                .andExpect(queryParam("direct", "false"))
                .andExpect(queryParam("departure_at", "2026-12"))
                .andExpect(queryParam("token", "secret-token"))
                .andRespond(withSuccess("""
                        {"success":true,"currency":"eur","data":[{"origin":"PAR","destination":"ABJ",
                        "origin_airport":"CDG","destination_airport":"ABJ","departure_at":"2026-12-04T11:30:00+01:00",
                        "price":327,"airline":"U2","flight_number":"4541","transfers":2,"duration":1870,
                        "gate":"Vayama","link":"/search/PAR0412ABJ1?t=AB1"}]}
                        """, MediaType.APPLICATION_JSON));

        List<TravelpayoutsPrice> prices = client.pricesForDates("PAR", "ABJ", FlightDate.ofMonth(YearMonth.of(2026, 12)), null,
                false, "EUR", 30);

        assertThat(prices).hasSize(1);
        TravelpayoutsPrice p = prices.get(0);
        assertThat(p.price()).isEqualByComparingTo(new BigDecimal("327"));
        assertThat(p.originAirport()).isEqualTo("CDG");
        assertThat(p.transfers()).isEqualTo(2);
        assertThat(p.duration()).isEqualTo(1870);
        assertThat(p.link()).isEqualTo("/search/PAR0412ABJ1?t=AB1");
        server.verify();
    }

    @Test
    void pricesForDates_roundTrip_sendsReturnMonth() {
        server.expect(requestTo(startsWith("https://api.test/aviasales/v3/prices_for_dates")))
                .andExpect(queryParam("one_way", "false"))
                .andExpect(queryParam("return_at", "2027-01-08"))
                .andRespond(withSuccess("{\"success\":true,\"data\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.pricesForDates("PAR", "DKR", null, FlightDate.ofDay(java.time.LocalDate.of(2027, 1, 8)), true, "XOF", 30)).isEmpty();
        server.verify();
    }

    @Test
    void pricesForDates_unsuccessfulBody_throwsProviderException() {
        server.expect(requestTo(startsWith("https://api.test/aviasales/v3/prices_for_dates")))
                .andRespond(withSuccess("{\"success\":false,\"data\":null}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.pricesForDates("PAR", "ABJ", null, null, false, "EUR", 30))
                .isInstanceOf(FlightProviderException.class);
    }

    @Test
    void pricesForDates_httpError_throwsProviderException() {
        server.expect(requestTo(startsWith("https://api.test/aviasales/v3/prices_for_dates")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.pricesForDates("PAR", "ABJ", null, null, false, "EUR", 30))
                .isInstanceOf(FlightProviderException.class)
                .hasMessageNotContaining("secret-token");
    }

    @Test
    void places_parsesCities() {
        server.expect(requestTo(startsWith("https://auto.test/places2")))
                .andExpect(queryParam("term", "Abidjan"))
                .andExpect(queryParam("locale", "fr"))
                .andRespond(withSuccess("""
                        [{"type":"city","code":"ABJ","name":"Abidjan","country_code":"CI","country_name":"Côte d'Ivoire"}]
                        """, MediaType.APPLICATION_JSON));

        List<TravelpayoutsPlace> places = client.places("Abidjan", "fr");

        assertThat(places).singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo("ABJ");
            assertThat(p.countryCode()).isEqualTo("CI");
        });
    }

    @Test
    void airlineNames_mapsCodesToNames_ignoringIncompleteRows() {
        server.expect(requestTo("https://api.test/data/fr/airlines.json"))
                .andRespond(withSuccess("""
                        [{"code":"u2","name":"easyJet"},{"code":"AT","name":"Royal Air Maroc"},
                         {"code":"ZZ","name":""},{"code":null,"name":"Sans code"}]
                        """, MediaType.APPLICATION_JSON));

        Map<String, String> names = client.airlineNames("fr");

        assertThat(names).containsOnly(Map.entry("U2", "easyJet"), Map.entry("AT", "Royal Air Maroc"));
    }

    @Test
    void isConfigured_dependsOnToken() {
        assertThat(client.isConfigured()).isTrue();
        assertThat(new TravelpayoutsClient(RestClient.create(), " ", "https://a", "https://b").isConfigured()).isFalse();
        assertThat(new TravelpayoutsClient(RestClient.create(), null, "https://a", "https://b").isConfigured()).isFalse();
    }
}
