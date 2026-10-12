package com.yadony.api.flights;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.yadony.api.flights.FlightTestData.CLOCK;
import static com.yadony.api.flights.FlightTestData.alert;
import static com.yadony.api.flights.FlightTestData.price;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlightPriceAlertSchedulerTest {

    @Mock FlightPriceAlertRepository alerts;
    @Mock FlightPriceService prices;
    @Mock NotificationDispatcher notifications;

    FlightPriceAlertScheduler scheduler;
    final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        scheduler = new FlightPriceAlertScheduler(alerts, prices, notifications, true, CLOCK);
        lenient().when(prices.isAvailable()).thenReturn(true);
        lenient().when(notifications.messagesFor(any())).thenReturn(TestMessages.fr());
    }

    @Test
    void priceUnderThreshold_notifiesWithRouteAndPrice() {
        FlightPriceAlertEntity a = alert(owner, "400");
        when(alerts.findAllByActiveTrue()).thenReturn(List.of(a));
        when(prices.prices(any())).thenReturn(List.of(price("520"), price("327")));

        scheduler.run();

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(notifications).notifyUser(eq(owner), title.capture(), body.capture(), data.capture());
        assertThat(title.getValue()).contains("Paris → Abidjan");
        assertThat(body.getValue()).contains("327 €").contains("400 €");
        assertThat(data.getValue()).containsEntry("type", "FLIGHT_PRICE_ALERT")
                .containsEntry("alertId", a.getId().toString());
        assertThat(a.getLastNotifiedPrice()).isEqualByComparingTo("327");
        verify(alerts).save(a);
    }

    @Test
    void priceAboveThreshold_updatesWithoutNotifying() {
        FlightPriceAlertEntity a = alert(owner, "300");
        when(alerts.findAllByActiveTrue()).thenReturn(List.of(a));
        when(prices.prices(any())).thenReturn(List.of(price("327")));

        scheduler.run();

        verify(notifications, never()).notifyUser(any(), anyString(), anyString(), anyMap());
        assertThat(a.getLastSeenPrice()).isEqualByComparingTo("327");
        verify(alerts).save(a);
    }

    @Test
    void identicalAlerts_shareOnePriceCall() {
        FlightPriceAlertEntity a = alert(owner, "400");
        FlightPriceAlertEntity b = alert(UUID.randomUUID(), "350");
        when(alerts.findAllByActiveTrue()).thenReturn(List.of(a, b));
        when(prices.prices(any())).thenReturn(List.of(price("327")));

        scheduler.run();

        verify(prices, times(1)).prices(any());
        verify(notifications, times(2)).notifyUser(any(), anyString(), anyString(), anyMap());
    }

    @Test
    void providerFailure_leavesAlertsUntouched() {
        FlightPriceAlertEntity a = alert(owner, "400");
        when(alerts.findAllByActiveTrue()).thenReturn(List.of(a));
        when(prices.prices(any())).thenThrow(new FlightProviderException("down"));

        scheduler.run();

        verify(alerts, never()).save(any());
        assertThat(a.getLastCheckedAt()).isNull();
    }

    @Test
    void expiredAlert_isPausedAndNotChecked() {
        FlightPriceAlertEntity a = alert(owner, "400");
        a.setDepartureMonth("2026-09");
        when(alerts.findAllByActiveTrue()).thenReturn(List.of(a));

        scheduler.run();

        assertThat(a.isActive()).isFalse();
        verify(alerts).save(a);
        verify(prices, never()).prices(any());
    }

    @Test
    void secondRunWithSamePrice_sendsNothingMore() {
        FlightPriceAlertEntity a = alert(owner, "400");
        when(alerts.findAllByActiveTrue()).thenReturn(List.of(a));
        when(prices.prices(any())).thenReturn(List.of(price("327")));

        scheduler.run();
        scheduler.run();

        verify(notifications, times(1)).notifyUser(any(), anyString(), anyString(), anyMap());
    }

    @Test
    void notificationFailure_doesNotStopOtherAlerts() {
        FlightPriceAlertEntity a = alert(owner, "400");
        FlightPriceAlertEntity b = alert(UUID.randomUUID(), "400");
        b.setDestinationCode("DKR");
        when(alerts.findAllByActiveTrue()).thenReturn(List.of(a, b));
        when(prices.prices(any())).thenReturn(List.of(price("327")));
        doThrow(new RuntimeException("fcm down")).doNothing()
                .when(notifications).notifyUser(any(), anyString(), anyString(), anyMap());

        scheduler.run();

        verify(notifications, times(2)).notifyUser(any(), anyString(), anyString(), anyMap());
    }

    @Test
    void disabledOrUnconfigured_doesNothing() {
        new FlightPriceAlertScheduler(alerts, prices, notifications, false, CLOCK).run();
        when(prices.isAvailable()).thenReturn(false);
        scheduler.run();

        verifyNoInteractions(alerts);
    }

    @Test
    void isExpired_onlyForPastDepartureMonth() {
        FlightPriceAlertEntity a = alert(owner, "400");
        YearMonth october = YearMonth.of(2026, 10);
        assertThat(FlightPriceAlertScheduler.isExpired(a, october)).isFalse();
        a.setDepartureMonth("2026-10");
        assertThat(FlightPriceAlertScheduler.isExpired(a, october)).isFalse();
        a.setDepartureMonth("2026-09");
        assertThat(FlightPriceAlertScheduler.isExpired(a, october)).isTrue();
    }

    @Test
    void formatPrice_euroSymbolOtherwiseCode() {
        assertThat(FlightPriceAlertScheduler.formatPrice(new BigDecimal("327.40"), "EUR")).isEqualTo("327 €");
        assertThat(FlightPriceAlertScheduler.formatPrice(new BigDecimal("215000"), "XOF")).isEqualTo("215000 XOF");
    }
}
