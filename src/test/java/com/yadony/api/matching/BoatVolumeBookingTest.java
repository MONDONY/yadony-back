package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Trajet bateau au m³ sans kilos (lot 3, BA3) : reconnaissance par l'entité, garde de réservation
 * provisoire et capacité en kilos nulle tolérée.
 */
@DisplayName("Trajet bateau au m³ : entité et garde de réservation")
class BoatVolumeBookingTest {

    private static AnnouncementEntity perM3(String availableVolume) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTransportMode(TransportMode.BOAT);
        a.setPricePerM3(new BigDecimal("600"));
        a.setVolumeCapacityM3(new BigDecimal("2.000"));
        a.setAvailableVolumeM3(availableVolume == null ? null : new BigDecimal(availableVolume));
        a.setCapacityUnit(CapacityUnit.SUITCASE_23KG);
        a.setStatus(AnnouncementStatus.ACTIVE);
        return a;
    }

    private static AnnouncementEntity perKg(TransportMode mode, String availableKg) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTransportMode(mode);
        a.setAvailableKg(availableKg == null ? null : new BigDecimal(availableKg));
        a.setPricePerKg(new BigDecimal("8"));
        a.setCapacityUnit(CapacityUnit.KG_EXACT);
        a.setStatus(AnnouncementStatus.ACTIVE);
        return a;
    }

    @Test
    @DisplayName("au m³ : bateau avec prix au m³ ; un bateau au kilo ou un autre mode ne l'est pas")
    void isPricedPerM3() {
        assertThat(perM3("2").isPricedPerM3()).isTrue();
        assertThat(perKg(TransportMode.BOAT, "10").isPricedPerM3()).isFalse();
        AnnouncementEntity plane = perKg(TransportMode.PLANE, "10");
        plane.setPricePerM3(new BigDecimal("600"));
        assertThat(plane.isPricedPerM3()).isFalse();
    }

    @Test
    @DisplayName("réservation d'un trajet au m³ : 422 boat-volume-booking-unavailable")
    void assertBookableByKg_perM3_422() {
        assertThatThrownBy(() -> BoatVolumeBooking.assertBookableByKg(perM3("2")))
                .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("boat-volume-booking-unavailable");
                });
    }

    @Test
    @DisplayName("réservation d'un trajet au kilo, bateau compris, ou sans trajet : rien")
    void assertBookableByKg_perKg_passes() {
        assertThatCode(() -> BoatVolumeBooking.assertBookableByKg(perKg(TransportMode.BOAT, "10")))
                .doesNotThrowAnyException();
        assertThatCode(() -> BoatVolumeBooking.assertBookableByKg(perKg(TransportMode.PLANE, "10")))
                .doesNotThrowAnyException();
        assertThatCode(() -> BoatVolumeBooking.assertBookableByKg(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("plus de place : volume à 0 au m³, kilos à 0 au kilo ; kilo libre et inconnu jamais pleins")
    void hasNoCapacityLeft() {
        assertThat(perM3("0").hasNoCapacityLeft()).isTrue();
        assertThat(perM3("0.5").hasNoCapacityLeft()).isFalse();
        assertThat(perM3(null).hasNoCapacityLeft()).isFalse();
        assertThat(perKg(TransportMode.PLANE, "0").hasNoCapacityLeft()).isTrue();
        assertThat(perKg(TransportMode.PLANE, "3").hasNoCapacityLeft()).isFalse();
        assertThat(perKg(TransportMode.PLANE, null).hasNoCapacityLeft()).isFalse();
        AnnouncementEntity free = perKg(TransportMode.PLANE, "0");
        free.setCapacityUnit(CapacityUnit.KG_FREE);
        assertThat(free.hasNoCapacityLeft()).isFalse();
    }

    @Test
    @DisplayName("kilos nuls (trajet au m³) : réserver ou rendre du poids ne change rien, sans exception")
    void reserveAndRelease_nullKg_noOp() {
        AnnouncementEntity trip = perM3("2");

        assertThat(trip.reserveCapacity(new BigDecimal("5"))).isFalse();
        assertThat(trip.releaseCapacity(new BigDecimal("5"))).isFalse();
        assertThat(trip.getAvailableKg()).isNull();
        assertThat(trip.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
    }

    @Test
    @DisplayName("trajet au kilo : réservation inchangée")
    void reserve_perKg_unchanged() {
        AnnouncementEntity trip = perKg(TransportMode.PLANE, "5");

        assertThat(trip.reserveCapacity(new BigDecimal("5"))).isTrue();
        assertThat(trip.getAvailableKg()).isEqualByComparingTo("0");
        assertThat(trip.getStatus()).isEqualTo(AnnouncementStatus.FULL);
        assertThat(trip.releaseCapacity(new BigDecimal("2"))).isTrue();
        assertThat(trip.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
    }
}
