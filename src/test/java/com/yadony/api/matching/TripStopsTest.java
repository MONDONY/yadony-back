package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripStopsTest {

    @Test
    void normalize_keepsValueForPlane() {
        assertThat(TripStops.normalize(0, TransportMode.PLANE)).isZero();
        assertThat(TripStops.normalize(1, TransportMode.PLANE)).isEqualTo(1);
        assertThat(TripStops.normalize(2, TransportMode.PLANE)).isEqualTo(2);
    }

    @Test
    void normalize_nullStaysNull() {
        assertThat(TripStops.normalize(null, TransportMode.PLANE)).isNull();
    }

    @Test
    void normalize_dropsValueOutsidePlane() {
        for (TransportMode mode : TransportMode.values()) {
            if (mode != TransportMode.PLANE) {
                assertThat(TripStops.normalize(1, mode)).as(mode.name()).isNull();
            }
        }
        assertThat(TripStops.normalize(1, null)).isNull();
    }

    @Test
    void normalize_rejectsOutOfRangeWith422() {
        for (int invalid : new int[] {-1, 3, 10}) {
            assertThatThrownBy(() -> TripStops.normalize(invalid, TransportMode.PLANE))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException ex = (YadonyBusinessException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("invalid-stops-count");
                    });
        }
    }

    @Test
    void normalizeOnUpdate_absentKeepsCurrent_presentReplaces_nonPlaneClears() {
        assertThat(TripStops.normalizeOnUpdate(null, 1, TransportMode.PLANE)).isEqualTo(1);
        assertThat(TripStops.normalizeOnUpdate(0, 1, TransportMode.PLANE)).isZero();
        assertThat(TripStops.normalizeOnUpdate(null, 1, TransportMode.CAR)).isNull();
    }

    @Test
    void searchBound_onlyZeroAndOneFilter() {
        assertThat(TripStops.searchBound(0)).isZero();
        assertThat(TripStops.searchBound(1)).isEqualTo(1);
        assertThat(TripStops.searchBound(2)).isNull();
        assertThat(TripStops.searchBound(7)).isNull();
        assertThat(TripStops.searchBound(-1)).isNull();
        assertThat(TripStops.searchBound(null)).isNull();
    }
}
