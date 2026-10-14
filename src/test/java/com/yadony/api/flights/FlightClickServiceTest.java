package com.yadony.api.flights;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.flights.dto.FlightClickRequest;
import com.yadony.api.flights.dto.FlightPassengers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlightClickServiceTest {

    @Mock FlightClickRepository clicks;
    @Mock FlightUsers users;

    final UUID owner = UUID.randomUUID();
    static final String UID = "uid-click";
    static final String PATH = "/search/PAR0412ABJ1?t=AT1793_5c55&search_date=09102026&static_fare_key=TY%7CP0";

    @BeforeEach
    void setUp() {
        lenient().when(users.resolve(UID)).thenReturn(new FlightUsers.Caller(owner, "fr"));
    }

    private FlightClickService service(String marker, boolean subId) {
        return new FlightClickService(clicks, users, "https://www.aviasales.com/", marker, subId);
    }

    private static FlightClickRequest request(String path) {
        return new FlightClickRequest(path, "par", "abj", new BigDecimal("327"), "eur", "at", "Kiwi.com", "deal");
    }

    @Test
    void click_buildsAffiliateUrlOnOurDomain_andRecordsTheClick() {
        var response = service("12345", false).click(UID, request(PATH));

        assertThat(response.url()).isEqualTo("https://www.aviasales.com" + PATH + "&marker=12345&currency=eur");
        ArgumentCaptor<FlightClickEntity> saved = ArgumentCaptor.forClass(FlightClickEntity.class);
        verify(clicks).save(saved.capture());
        FlightClickEntity click = saved.getValue();
        assertThat(click.getOwnerId()).isEqualTo(owner);
        assertThat(click.getOriginCode()).isEqualTo("PAR");
        assertThat(click.getAirlineCode()).isEqualTo("AT");
        assertThat(click.getCurrency()).isEqualTo("EUR");
        assertThat(click.getSource()).isEqualTo("deal");
        assertThat(click.getSubId()).matches("[a-z0-9]{12}");
    }

    @Test
    void click_withSubId_appendsItToTheMarker() {
        var response = service("12345", true).click(UID, request("/search/PAR0412ABJ1"));

        ArgumentCaptor<FlightClickEntity> saved = ArgumentCaptor.forClass(FlightClickEntity.class);
        verify(clicks).save(saved.capture());
        assertThat(response.url()).isEqualTo("https://www.aviasales.com/search/PAR0412ABJ1?marker=12345."
                + saved.getValue().getSubId() + "&currency=eur");
    }

    @Test
    void click_rejectsPathsThatCouldLeaveThePartnerSite() {
        FlightClickService service = service("12345", false);
        for (String bad : new String[]{"https://evil.test/search/x", "//evil.test/search", "/account/delete",
                "/search/../admin", "/search/x y", "/search//evil.test",
                "search/PAR0112ABJ1", "/SEARCH/PAR0112ABJ1", "javascript:alert(1)", "\\\\evil.test/search/x"}) {
            assertThatThrownBy(() -> service.click(UID, request(bad)))
                    .as(bad)
                    .isInstanceOf(YadonyBusinessException.class);
        }
        verify(clicks, never()).save(any());
    }

    @Test
    void click_acceptsTheRouteSearchPath_forSeeAllAirlines() {
        var response = service("12345", false).click(UID,
                new FlightClickRequest("/search/PAR0112ABJ01011", "PAR", "ABJ", null, "EUR", null, null, "search"));

        assertThat(response.url()).isEqualTo("https://www.aviasales.com/search/PAR0112ABJ01011?marker=12345&currency=eur");
        verify(clicks).save(any());
    }

    @Test
    void click_withoutMarker_is503() {
        assertThatThrownBy(() -> service(" ", false).click(UID, request(PATH)))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("flights-unavailable");
    }

    @Test
    void click_statisticFailure_stillReturnsTheLink() {
        when(clicks.save(any())).thenThrow(new RuntimeException("db down"));

        assertThat(service("12345", false).click(UID, request(PATH)).url()).contains("marker=12345");
    }

    private static FlightClickRequest request(String path, String currency) {
        return new FlightClickRequest(path, "PAR", "ABJ", new BigDecimal("327"), currency, "AF", null, "search");
    }

    /** Régression : sans devise, aviasales.com s'ouvrait en dollars US alors que l'app affichait des euros. */
    @Test
    void click_carriesTheOfferCurrency_inLowerCase() {
        FlightClickService service = service("12345", false);

        assertThat(service.click(UID, request("/search/PAR2312ABJ1", "EUR")).url())
                .isEqualTo("https://www.aviasales.com/search/PAR2312ABJ1?marker=12345&currency=eur");
        assertThat(service.click(UID, request("/search/PAR2312ABJ1", "gbp")).url()).endsWith("&currency=gbp");
        assertThat(service.click(UID, request("/search/PAR2312ABJ1", "USD")).url()).endsWith("&currency=usd");
        assertThat(service.click(UID, request("/search/PAR2312ABJ1", "CAD")).url()).endsWith("&currency=cad");
    }

    @Test
    void click_withoutCurrency_orOneTheSiteIgnores_usesEuro() {
        FlightClickService service = service("12345", false);

        assertThat(service.click(UID, request("/search/PAR2312ABJ1", null)).url()).endsWith("?marker=12345&currency=eur");
        for (String c : new String[]{"XOF", "XAF", "MAD", "CHF", "  "}) {
            assertThat(service.click(UID, request("/search/PAR2312ABJ1", c)).url()).as(c).endsWith("&currency=eur");
        }
    }

    @Test
    void click_pathWithParameters_keepsThem_andNeverDuplicatesCurrencyOrMarker() {
        String url = service("12345", false).click(UID,
                request("/search/PAR0412ABJ1?t=AT1793_5c55&currency=usd&marker=99999&static_fare_key=TY%7CP0", "EUR")).url();

        assertThat(url).isEqualTo("https://www.aviasales.com/search/PAR0412ABJ1?t=AT1793_5c55"
                + "&static_fare_key=TY%7CP0&marker=12345&currency=eur");
    }

    @Test
    void click_keepsRawPartnerCharacters_withoutReencoding() {
        String url = service("12345", false).click(UID, request("/search/PAR0412ABJ1?t=A|B+C~D", "EUR")).url();

        assertThat(url).isEqualTo("https://www.aviasales.com/search/PAR0412ABJ1?t=A|B+C~D&marker=12345&currency=eur");
    }

    @Test
    void click_subIdMarker_isEncoded() {
        FlightClickService service = new FlightClickService(clicks, users, "https://www.aviasales.com", "12 3", false);
        assertThat(service.click(UID, request("/search/PAR2312ABJ1", "EUR")).url())
                .isEqualTo("https://www.aviasales.com/search/PAR2312ABJ1?marker=12+3&currency=eur");
    }

    // --- Voyageurs (retour testeur FLUTTER-PJ) ---

    private static FlightClickRequest paxRequest(String path, FlightPassengers passengers) {
        return new FlightClickRequest(path, "PAR", "ABJ", new BigDecimal("327"), "EUR", "AF", null, "search", passengers);
    }

    private String url(String path, FlightPassengers passengers) {
        return service("12345", false).click(UID, paxRequest(path, passengers)).url();
    }

    private static FlightPassengers pax(int adults, int children, int infants) {
        return new FlightPassengers(adults, children, infants);
    }

    @Test
    void passengers_absent_leavesTheLinkUntouched() {
        assertThat(url(PATH, null)).isEqualTo("https://www.aviasales.com" + PATH + "&marker=12345&currency=eur");
        verify(clicks).save(argThat(c -> c.getPassengers() == null));
    }

    @Test
    void passengers_singleAdult_leavesTheLinkUntouched() {
        assertThat(url(PATH, pax(1, 0, 0))).isEqualTo("https://www.aviasales.com" + PATH + "&marker=12345&currency=eur");
        verify(clicks).save(argThat(c -> c.getPassengers() == 1));
    }

    @Test
    void passengers_oneWay_withoutQuery_rewritesTheSuffix() {
        assertThat(url("/search/PAR1212ABJ1", pax(2, 0, 0)))
                .isEqualTo("https://www.aviasales.com/search/PAR1212ABJ2?marker=12345&currency=eur");
        assertThat(url("/search/PAR1212ABJ1", pax(2, 1, 0)))
                .isEqualTo("https://www.aviasales.com/search/PAR1212ABJ21?marker=12345&currency=eur");
        assertThat(url("/search/PAR1212ABJ1", pax(2, 0, 1)))
                .isEqualTo("https://www.aviasales.com/search/PAR1212ABJ201?marker=12345&currency=eur");
    }

    @Test
    void passengers_roundTrip_withoutQuery_rewritesTheSuffix() {
        assertThat(url("/search/PAR0112ABJ01011", pax(2, 0, 0)))
                .isEqualTo("https://www.aviasales.com/search/PAR0112ABJ01012?marker=12345&currency=eur");
        assertThat(url("/search/PAR0112ABJ01011", pax(3, 2, 1)))
                .isEqualTo("https://www.aviasales.com/search/PAR0112ABJ0101321?marker=12345&currency=eur");
    }

    @Test
    void passengers_offerWithFareParams_dropsThem_andKeepsTheRest() {
        assertThat(url(PATH, pax(2, 1, 0)))
                .isEqualTo("https://www.aviasales.com/search/PAR0412ABJ21?marker=12345&currency=eur");
        assertThat(url("/search/PAR0412ABJ09121?t=AT1793_5c55&adults_hint=x&currency=usd", pax(2, 0, 1)))
                .isEqualTo("https://www.aviasales.com/search/PAR0412ABJ0912201?adults_hint=x&marker=12345&currency=eur");
    }

    @Test
    void passengers_keepTheTripClassLetter() {
        assertThat(url("/search/PAR0412ABJc1", pax(2, 0, 0)))
                .isEqualTo("https://www.aviasales.com/search/PAR0412ABJc2?marker=12345&currency=eur");
    }

    @Test
    void passengers_unknownPathShape_keepsTheSingleAdultLink() {
        assertThat(url("/search/X?t=1", pax(2, 0, 0))).isEqualTo("https://www.aviasales.com/search/X?t=1&marker=12345&currency=eur");
    }

    @Test
    void passengers_storeTheTotal() {
        url(PATH, pax(2, 1, 1));
        verify(clicks).save(argThat(c -> c.getPassengers() == 4));
    }

    @Test
    void passengersSuffix_omitsTrailingZeros() {
        assertThat(FlightClickService.passengersSuffix(pax(1, 0, 0))).isEqualTo("1");
        assertThat(FlightClickService.passengersSuffix(pax(9, 0, 0))).isEqualTo("9");
        assertThat(FlightClickService.passengersSuffix(pax(1, 3, 0))).isEqualTo("13");
        assertThat(FlightClickService.passengersSuffix(pax(4, 0, 4))).isEqualTo("404");
    }
}
