package com.yadony.api.admin;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.TransportMode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminTransportModeFilterTest {

    @Test
    void absentOuVide_pasDeFiltre() {
        assertThat(AdminTransportModeFilter.parse(null)).isNull();
        assertThat(AdminTransportModeFilter.parse("  ")).isNull();
    }

    @Test
    void any_peuImporte_sansMode() {
        AdminTransportModeFilter f = AdminTransportModeFilter.parse(" any ");
        assertThat(f.any()).isTrue();
        assertThat(f.mode()).isNull();
        assertThat(f.modes()).isEmpty();
    }

    @Test
    void route_voitureEtBus() {
        AdminTransportModeFilter f = AdminTransportModeFilter.parse("car");
        assertThat(f.any()).isFalse();
        assertThat(f.mode()).isEqualTo(TransportMode.CAR);
        assertThat(f.modes()).containsExactlyInAnyOrder(TransportMode.CAR, TransportMode.BUS);
        assertThat(AdminTransportModeFilter.parse("BUS").modes())
                .containsExactlyInAnyOrder(TransportMode.CAR, TransportMode.BUS);
        assertThat(AdminTransportModeFilter.parse("PLANE").modes()).containsExactly(TransportMode.PLANE);
    }

    @Test
    void valeurInconnue_422() {
        assertThatThrownBy(() -> AdminTransportModeFilter.parse("ROCKET"))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("invalid-transport-mode");
                });
    }
}
