package com.yadony.api.flights;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.flights.dto.FlightClickRequest;
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

        assertThat(response.url()).isEqualTo("https://www.aviasales.com" + PATH + "&marker=12345");
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
                + saved.getValue().getSubId());
    }

    @Test
    void click_rejectsPathsThatCouldLeaveThePartnerSite() {
        FlightClickService service = service("12345", false);
        for (String bad : new String[]{"https://evil.test/search/x", "//evil.test/search", "/account/delete",
                "/search/../admin", "/search/x y", "/search//evil.test"}) {
            assertThatThrownBy(() -> service.click(UID, request(bad)))
                    .as(bad)
                    .isInstanceOf(YadonyBusinessException.class);
        }
        verify(clicks, never()).save(any());
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
}
