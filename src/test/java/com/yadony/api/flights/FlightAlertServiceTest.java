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
        lenient().when(users.resolve(UID)).thenReturn(new FlightUsers.Caller(owner, "fr"));
        lenient().when(alerts.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static FlightAlertRequest request(String max) {
        return new FlightAlertRequest("par", "abj", " Paris ", "Abidjan", "2026-12", null, false,
                new BigDecimal(max), "eur");
    }

    @Test
    void create_normalizesAndSaves() {
        when(alerts.findAllByOwnerIdOrderByCreatedAtDesc(owner)).thenReturn(List.of());

        var response = service.create(UID, request("399.999"));

        assertThat(response.originCode()).isEqualTo("PAR");
        assertThat(response.destinationCode()).isEqualTo("ABJ");
        assertThat(response.originLabel()).isEqualTo("Paris");
        assertThat(response.departureMonth()).isEqualTo("2026-12");
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
        existing.setDepartureMonth("2026-12");
        when(alerts.findAllByOwnerIdOrderByCreatedAtDesc(owner)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.create(UID, request("400")))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("flight-alert-duplicate");
    }

    @Test
    void create_validatesQuery() {
        assertThatThrownBy(() -> service.create(UID, new FlightAlertRequest("PAR", "PAR", null, null, null, null,
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
}
