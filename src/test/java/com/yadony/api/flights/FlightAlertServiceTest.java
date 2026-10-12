package com.yadony.api.flights;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.flights.dto.FlightAlertRequest;
import com.yadony.api.flights.dto.FlightAlertUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.flights.FlightTestData.CLOCK;
import static com.yadony.api.flights.FlightTestData.alert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlightAlertServiceTest {

    @Mock FlightPriceAlertRepository alerts;
    @Mock FlightUsers users;

    FlightAlertService service;
    final UUID owner = UUID.randomUUID();
    static final String UID = "uid-1";

    @BeforeEach
    void setUp() {
        service = new FlightAlertService(alerts, users, CLOCK);
        lenient().when(users.resolveAccount(UID)).thenReturn(new FlightUsers.Caller(owner, "fr"));
        lenient().when(alerts.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static FlightAlertRequest request(String max) {
        return new FlightAlertRequest("par", "abj", " Paris ", "Abidjan", LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 31), null, null, false, new BigDecimal(max), "eur");
    }

    @Test
    void create_normalizesAndSaves() {
        when(alerts.findAllByOwnerIdOrderByCreatedAtDesc(owner)).thenReturn(List.of());

        var response = service.create(UID, request("399.999"));

        assertThat(response.originCode()).isEqualTo("PAR");
        assertThat(response.destinationCode()).isEqualTo("ABJ");
        assertThat(response.originLabel()).isEqualTo("Paris");
        assertThat(response.departureFrom()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(response.departureTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(response.maxPrice()).isEqualByComparingTo("400.00");
        assertThat(response.currency()).isEqualTo("EUR");
        assertThat(response.active()).isTrue();
    }

    @Test
    void create_rejectsBeyondLimit() {
        List<FlightPriceAlertEntity> full = new ArrayList<>(Collections.nCopies(FlightAlertService.MAX_ALERTS,
                alert(owner, "100")));
        when(alerts.findAllByOwnerIdOrderByCreatedAtDesc(owner)).thenReturn(full);

        assertThatThrownBy(() -> service.create(UID, request("400")))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("flight-alert-limit-reached");
        verify(alerts, never()).save(any());
    }

    @Test
    void create_rejectsDuplicate() {
        FlightPriceAlertEntity existing = alert(owner, "300");
        existing.setDepartureFrom(LocalDate.of(2026, 12, 1));
        existing.setDepartureTo(LocalDate.of(2026, 12, 31));
        when(alerts.findAllByOwnerIdOrderByCreatedAtDesc(owner)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.create(UID, request("400")))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("flight-alert-duplicate");
    }

    @Test
    void create_validatesQuery() {
        assertThatThrownBy(() -> service.create(UID, new FlightAlertRequest("PAR", "PAR", null, null, null, null, null, null,
                false, BigDecimal.TEN, null)))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void list_returnsOwnerAlerts() {
        when(alerts.findAllByOwnerIdOrderByCreatedAtDesc(owner)).thenReturn(List.of(alert(owner, "400")));

        assertThat(service.list(UID)).singleElement().satisfies(r -> assertThat(r.originCode()).isEqualTo("PAR"));
    }

    @Test
    void update_pauseAndNewThresholdResetsAntiSpam() {
        FlightPriceAlertEntity a = alert(owner, "400");
        a.setLastNotifiedPrice(new BigDecimal("350"));
        when(alerts.findByIdAndOwnerId(a.getId(), owner)).thenReturn(Optional.of(a));

        var response = service.update(UID, a.getId(), new FlightAlertUpdateRequest(false, new BigDecimal("330")));

        assertThat(response.active()).isFalse();
        assertThat(response.maxPrice()).isEqualByComparingTo("330");
        assertThat(a.getLastNotifiedPrice()).isNull();
    }

    @Test
    void update_sameThreshold_keepsAntiSpam() {
        FlightPriceAlertEntity a = alert(owner, "400.00");
        a.setLastNotifiedPrice(new BigDecimal("350"));
        when(alerts.findByIdAndOwnerId(a.getId(), owner)).thenReturn(Optional.of(a));

        service.update(UID, a.getId(), new FlightAlertUpdateRequest(null, new BigDecimal("400")));

        assertThat(a.getLastNotifiedPrice()).isEqualByComparingTo("350");
        assertThat(a.isActive()).isTrue();
    }

    @Test
    void update_otherUsersAlert_is404() {
        UUID id = UUID.randomUUID();
        when(alerts.findByIdAndOwnerId(id, owner)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(UID, id, new FlightAlertUpdateRequest(true, null)))
                .isInstanceOf(YadonyNotFoundException.class);
    }

    @Test
    void delete_softDeletesAndDeactivates() {
        FlightPriceAlertEntity a = alert(owner, "400");
        when(alerts.findByIdAndOwnerId(a.getId(), owner)).thenReturn(Optional.of(a));

        service.delete(UID, a.getId());

        assertThat(a.getDeletedAt()).isNotNull();
        assertThat(a.isActive()).isFalse();
        verify(alerts).save(a);
    }

    private FlightAlertRequest period(LocalDate from, LocalDate to, LocalDate returnFrom, LocalDate returnTo) {
        return new FlightAlertRequest("PAR", "ABJ", null, null, from, to, returnFrom, returnTo, false,
                BigDecimal.TEN, "EUR");
    }

    private void assertCode(FlightAlertRequest request, String code) {
        assertThatThrownBy(() -> service.create(UID, request))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo(code);
    }

    @Test
    void create_period_isValidated() {
        LocalDate d = LocalDate.of(2026, 12, 1);
        assertCode(period(d, null, null, null), "flights-invalid-period");
        assertCode(period(d, d.minusDays(1), null, null), "flights-invalid-period");
        assertCode(period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, null), "flights-date-past");
        assertCode(period(d, d.plusDays(31), null, null), "flights-period-too-long");
        assertCode(period(d, d.plusDays(10), d.minusDays(20), d.minusDays(10)), "flights-return-before-departure");
    }

    @Test
    void create_period_clampsStartInThePast_andAcceptsRoundTrip() {
        when(alerts.findAllByOwnerIdOrderByCreatedAtDesc(owner)).thenReturn(List.of());

        var response = service.create(UID, period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 25),
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 20)));

        assertThat(response.departureFrom()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(response.returnFrom()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(response.returnTo()).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    @Test
    void create_withoutPeriod_watchesAllUpcomingDates() {
        when(alerts.findAllByOwnerIdOrderByCreatedAtDesc(owner)).thenReturn(List.of());

        var response = service.create(UID, period(null, null, null, null));

        assertThat(response.departureFrom()).isNull();
        assertThat(response.returnFrom()).isNull();
    }
}
