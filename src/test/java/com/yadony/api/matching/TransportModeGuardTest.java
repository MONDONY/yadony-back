package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.PlatformSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransportModeGuardTest {

    private final PlatformSettingsService settings = mock(PlatformSettingsService.class);
    private final TransportModeGuard guard = new TransportModeGuard(settings);

    private void roadAndBoat(boolean road, boolean boat) {
        when(settings.transportRoadEnabled()).thenReturn(road);
        when(settings.transportBoatEnabled()).thenReturn(boat);
    }

    @Test
    void boat_whenDisabled_rejected() {
        roadAndBoat(true, false);

        assertThatThrownBy(() -> guard.assertTripModeAllowed(TransportMode.BOAT, null))
                .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("transport-mode-disabled");
                    assertThat(ex.getMessage()).contains("bateau");
                });
    }

    @Test
    void boat_whenEnabled_accepted() {
        roadAndBoat(false, true);

        assertThatCode(() -> guard.assertTripModeAllowed(TransportMode.BOAT, null)).doesNotThrowAnyException();
    }

    @Test
    void boat_unchangedOnExistingTrip_accepted() {
        roadAndBoat(false, false);

        assertThatCode(() -> guard.assertTripModeAllowed(TransportMode.BOAT, TransportMode.BOAT))
                .doesNotThrowAnyException();
    }

    @Test
    void boat_switchedFromAnotherModeWhenDisabled_rejected() {
        roadAndBoat(false, false);

        assertThatThrownBy(() -> guard.assertTripModeAllowed(TransportMode.BOAT, TransportMode.PLANE))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void car_trip_alwaysAccepted() {
        roadAndBoat(false, false);

        for (TransportMode mode : new TransportMode[]{
                TransportMode.CAR, TransportMode.BUS, TransportMode.TRAIN, TransportMode.OTHER, TransportMode.PLANE}) {
            assertThatCode(() -> guard.assertTripModeAllowed(mode, null)).doesNotThrowAnyException();
        }
    }

    @Test
    void trip_nullMode_accepted() {
        roadAndBoat(false, false);

        assertThatCode(() -> guard.assertTripModeAllowed(null, null)).doesNotThrowAnyException();
    }

    @Test
    void request_car_whenRoadDisabled_rejected() {
        roadAndBoat(false, true);

        assertThatThrownBy(() -> guard.assertRequestModeAllowed(TransportMode.CAR))
                .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("transport-mode-disabled");
                });
        assertThatThrownBy(() -> guard.assertRequestModeAllowed(TransportMode.BUS))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void request_boat_whenBoatDisabled_rejected() {
        roadAndBoat(true, false);

        assertThatThrownBy(() -> guard.assertRequestModeAllowed(TransportMode.BOAT))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void request_enabledModes_accepted() {
        roadAndBoat(true, true);

        assertThatCode(() -> {
            guard.assertRequestModeAllowed(TransportMode.CAR);
            guard.assertRequestModeAllowed(TransportMode.BUS);
            guard.assertRequestModeAllowed(TransportMode.BOAT);
        }).doesNotThrowAnyException();
    }

    @Test
    void request_null_alwaysAccepted() {
        roadAndBoat(false, false);

        assertThatCode(() -> guard.assertRequestModeAllowed(null)).doesNotThrowAnyException();
    }

    @Test
    void request_planeTrainOther_alwaysAccepted() {
        roadAndBoat(false, false);

        assertThatCode(() -> {
            guard.assertRequestModeAllowed(TransportMode.PLANE);
            guard.assertRequestModeAllowed(TransportMode.TRAIN);
            guard.assertRequestModeAllowed(TransportMode.OTHER);
        }).doesNotThrowAnyException();
    }
}
