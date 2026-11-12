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

    @Test
    void parse_normalizesCaseAndSpaces() {
        assertThat(TransportMode.parse(" plane ")).isEqualTo(TransportMode.PLANE);
        assertThat(TransportMode.parse("Boat")).isEqualTo(TransportMode.BOAT);
    }

    @Test
    void parse_rejectsUnknownOrNullWith422() {
        for (String raw : new String[] {"SHIP", "", null}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> TransportMode.parse(raw))
                    .isInstanceOfSatisfying(com.yadony.api.common.YadonyBusinessException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.getErrorCode()).isEqualTo("invalid-transport-mode");
                    });
        }
    }

    @Test
    void parseOrNull_isLenientOnStoredValues() {
        assertThat(TransportMode.parseOrNull("bus")).isEqualTo(TransportMode.BUS);
        assertThat(TransportMode.parseOrNull("SHIP")).isNull();
        assertThat(TransportMode.parseOrNull(null)).isNull();
    }
}
