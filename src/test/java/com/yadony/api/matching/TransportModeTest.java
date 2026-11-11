package com.yadony.api.matching;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransportModeTest {

    @Test
    void null_acceptsEverything() {
        for (TransportMode offered : TransportMode.values()) {
            assertThat(TransportMode.accepts(null, offered)).isTrue();
        }
    }

    @Test
    void car_acceptsBus() {
        assertThat(TransportMode.accepts(TransportMode.CAR, TransportMode.BUS)).isTrue();
    }

    @Test
    void bus_acceptsCar() {
        assertThat(TransportMode.accepts(TransportMode.BUS, TransportMode.CAR)).isTrue();
    }

    @Test
    void plane_rejectsBoat() {
        assertThat(TransportMode.accepts(TransportMode.PLANE, TransportMode.BOAT)).isFalse();
    }

    @Test
    void sameMode_isAccepted() {
        for (TransportMode mode : TransportMode.values()) {
            assertThat(TransportMode.accepts(mode, mode)).isTrue();
        }
    }

    @Test
    void road_rejectsOtherModes() {
        assertThat(TransportMode.accepts(TransportMode.CAR, TransportMode.PLANE)).isFalse();
        assertThat(TransportMode.accepts(TransportMode.BUS, TransportMode.TRAIN)).isFalse();
    }

    @Test
    void requestedMode_rejectsOfferWithoutMode() {
        assertThat(TransportMode.accepts(TransportMode.PLANE, null)).isFalse();
    }
}
