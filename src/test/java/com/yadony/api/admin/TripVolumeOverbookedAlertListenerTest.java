package com.yadony.api.admin;

import com.yadony.api.matching.events.TripVolumeOverbookedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Surréservation au m³ : une alerte par trajet, type dans la colonne, échec d'alerte avalé. */
class TripVolumeOverbookedAlertListenerTest {

    @Test
    void raisesOncePerTrip_andSwallowsFailures() {
        AdminAlertEscalator escalator = mock(AdminAlertEscalator.class);
        TripVolumeOverbookedAlertListener listener = new TripVolumeOverbookedAlertListener(escalator);
        UUID trip = UUID.randomUUID();
        TripVolumeOverbookedEvent event = new TripVolumeOverbookedEvent(trip, UUID.randomUUID(), new BigDecimal("6"),
                new BigDecimal("4"));

        listener.onOverbooked(event);

        String type = TripVolumeOverbookedAlertListener.ALERT_PREFIX + trip;
        assertThat(type.length()).isLessThanOrEqualTo(AdminAlertEscalator.TYPE_MAX_LENGTH);
        verify(escalator).raiseOnce(eq(type), anyString(), anyMap());

        doThrow(new IllegalStateException("base indisponible")).when(escalator)
                .raiseOnce(anyString(), anyString(), any(Map.class));
        listener.onOverbooked(event);
    }
}
