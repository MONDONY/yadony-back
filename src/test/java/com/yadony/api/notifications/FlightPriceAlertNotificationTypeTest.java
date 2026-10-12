package com.yadony.api.notifications;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Le type FLIGHT_PRICE_ALERT (package flights) est réglable, classé, regroupé et ouvre la liste des alertes. */
class FlightPriceAlertNotificationTypeTest {

    @Test
    void controlledByTheAlertsPreference() {
        assertThat(NotificationPrefsService.TYPE_TO_PREF).containsEntry("FLIGHT_PRICE_ALERT", "pushCorridorAlerts");
    }

    @Test
    void classifiedWithTrips() {
        assertThat(NotificationCategory.fromType("FLIGHT_PRICE_ALERT")).isEqualTo(NotificationCategory.TRAJETS);
    }

    @Test
    void opensTheFlightAlertsList() {
        assertThat(NotificationDeeplink.of("FLIGHT_PRICE_ALERT", Map.of("alertId", UUID.randomUUID().toString())))
                .contains("yadony://flights/alerts");
    }

    @Test
    void groupedPerAlert() {
        String alertId = UUID.randomUUID().toString();
        assertThat(NotificationGroupKey.of("FLIGHT_PRICE_ALERT", Map.of("alertId", alertId)))
                .contains("flight-alert:" + alertId);
        assertThat(NotificationGroupKey.of("FLIGHT_PRICE_ALERT", Map.of("alertId", "not-a-uuid"))).isEmpty();
    }
}
