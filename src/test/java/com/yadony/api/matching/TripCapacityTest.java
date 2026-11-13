package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TripCapacity} : place prise et rendue par un colis, au kilo (règle de l'entité, inchangée pour l'avion)
 * ou au volume (bateau vendu au m³).
 */
@DisplayName("TripCapacity : capacité au kilo et au m³")
class TripCapacityTest {

    private static AnnouncementEntity plane(String availableKg) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTransportMode(TransportMode.PLANE);
        a.setCapacityUnit(CapacityUnit.KG_EXACT);
        a.setAvailableKg(new BigDecimal(availableKg));
        a.setTotalKg(new BigDecimal("20"));
        a.setPricePerKg(new BigDecimal("8"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return a;
    }

    private static AnnouncementEntity boatPerM3(String capacity, String available) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTransportMode(TransportMode.BOAT);
        a.setCapacityUnit(CapacityUnit.KG_EXACT);
        a.setAvailableKg(null);
        a.setTotalKg(null);
        a.setPricePerKg(null);
        a.setPricePerM3(new BigDecimal("600"));
        a.setVolumeCapacityM3(new BigDecimal(capacity));
        a.setAvailableVolumeM3(new BigDecimal(available));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return a;
    }

    private static BidEntity parcel(String weightKg, String volumeM3) {
        BidEntity b = new BidEntity();
        b.setWeightKg(weightKg == null ? null : new BigDecimal(weightKg));
        b.setVolumeM3(volumeM3 == null ? null : new BigDecimal(volumeM3));
        return b;
    }

    @Nested
    @DisplayName("bateau vendu au m³")
    class Boat {

        @Test
        @DisplayName("reserve_boat_decrementsVolume : 2,5 m³ - 0,096 m³ = 2,404 m³, kilos intacts (nuls)")
        void reserve_boat_decrementsVolume() {
            AnnouncementEntity trip = boatPerM3("2.5", "2.500");

            assertThat(TripCapacity.reserve(trip, parcel("12", "0.096"))).isTrue();

            assertThat(trip.getAvailableVolumeM3()).isEqualByComparingTo("2.404");
            assertThat(trip.getAvailableKg()).isNull();
            assertThat(trip.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
        }

        @Test
        @DisplayName("release_boat_restoresVolume : volume rendu, FULL → ACTIVE")
        void release_boat_restoresVolume() {
            AnnouncementEntity trip = boatPerM3("2.5", "0");
            trip.setStatus(AnnouncementStatus.FULL);

            assertThat(TripCapacity.release(trip, parcel("12", "0.096"))).isTrue();

            assertThat(trip.getAvailableVolumeM3()).isEqualByComparingTo("0.096");
            assertThat(trip.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
        }

        @Test
        @DisplayName("fullWhenVolumeExhausted : le dernier m³ pris → FULL")
        void fullWhenVolumeExhausted() {
            AnnouncementEntity trip = boatPerM3("2.5", "0.100");

            TripCapacity.reserve(trip, parcel(null, "0.100"));

            assertThat(trip.getAvailableVolumeM3()).isEqualByComparingTo("0");
            assertThat(trip.getStatus()).isEqualTo(AnnouncementStatus.FULL);
        }

        @Test
        @DisplayName("trajet hors marché (annulé, retiré, livré) : volume pris mais jamais de bascule FULL")
        void reserve_outOfMarket_neverFull() {
            for (AnnouncementStatus status : AnnouncementStatus.OUT_OF_MARKET) {
                AnnouncementEntity trip = boatPerM3("2.5", "0.100");
                trip.setStatus(status);

                TripCapacity.reserve(trip, parcel(null, "0.100"));

                assertThat(trip.getStatus()).as("%s", status).isEqualTo(status);
            }
        }

        @Test
        @DisplayName("surréservation d'un fil déjà payé : jamais sous zéro (chk_announcements_volume), FULL")
        void reserve_overbooked_clampedAtZero() {
            AnnouncementEntity trip = boatPerM3("2.5", "0.050");

            assertThat(TripCapacity.reserve(trip, parcel(null, "0.096"))).isTrue();

            assertThat(trip.getAvailableVolumeM3()).isEqualByComparingTo("0");
            assertThat(trip.getStatus()).isEqualTo(AnnouncementStatus.FULL);
        }

        @Test
        @DisplayName("rendu plafonné au volume proposé (trajet dédié qui garde tout son volume)")
        void release_cappedAtCapacity() {
            AnnouncementEntity trip = boatPerM3("0.800", "0.800");

            assertThat(TripCapacity.release(trip, parcel(null, "0.800"))).isTrue();

            assertThat(trip.getAvailableVolumeM3()).isEqualByComparingTo("0.800");
        }

        @Test
        @DisplayName("colis sans volume ou trajet sans volume : rien ne bouge, aucune bascule")
        void noVolume_noChange() {
            AnnouncementEntity trip = boatPerM3("2.5", "0");
            trip.setStatus(AnnouncementStatus.FULL);

            assertThat(TripCapacity.reserve(trip, parcel("5", null))).isFalse();
            assertThat(TripCapacity.release(trip, parcel("5", null))).isFalse();
            assertThat(trip.getStatus()).isEqualTo(AnnouncementStatus.FULL);

            AnnouncementEntity unknown = boatPerM3("2.5", "1");
            unknown.setAvailableVolumeM3(null);
            assertThat(TripCapacity.reserve(unknown, parcel(null, "0.1"))).isFalse();
            assertThat(TripCapacity.release(unknown, parcel(null, "0.1"))).isFalse();
        }

        @Test
        @DisplayName("acceptation : volume au-delà du restant → 409 insufficient-volume ; égal → accepté")
        void assertAcceptable_volume() {
            AnnouncementEntity trip = boatPerM3("2.5", "0.100");

            assertThatCode(() -> TripCapacity.assertAcceptable(trip, parcel("999", "0.100")))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> TripCapacity.assertAcceptable(trip, parcel(null, "0.101")))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getErrorCode()).isEqualTo("insufficient-volume");
                    });
        }

        @Test
        @DisplayName("réservation : le poids d'un colis au m³ n'est pas comparé à des kilos (trajet sans kilos)")
        void assertWeightBookable_perM3_ignoresWeight() {
            assertThatCode(() -> TripCapacity.assertWeightBookable(boatPerM3("2.5", "2.5"), new BigDecimal("500")))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("trajet au kilo : comportement inchangé")
    class Kg {

        @Test
        @DisplayName("reserve_plane_unchanged : 20 kg - 5 kg = 15 kg, ACTIVE ; 5 kg - 5 kg = 0, FULL")
        void reserve_plane_unchanged() {
            AnnouncementEntity trip = plane("20");
            assertThat(TripCapacity.reserve(trip, parcel("5", null))).isTrue();
            assertThat(trip.getAvailableKg()).isEqualByComparingTo("15");
            assertThat(trip.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);

            AnnouncementEntity exact = plane("5");
            TripCapacity.reserve(exact, parcel("5", null));
            assertThat(exact.getAvailableKg()).isEqualByComparingTo("0");
            assertThat(exact.getStatus()).isEqualTo(AnnouncementStatus.FULL);
        }

        @Test
        @DisplayName("release_plane_unchanged : 20 kg + 5 kg = 25 kg, FULL → ACTIVE")
        void release_plane_unchanged() {
            AnnouncementEntity trip = plane("20");
            trip.setStatus(AnnouncementStatus.FULL);

            assertThat(TripCapacity.release(trip, parcel("5", null))).isTrue();

            assertThat(trip.getAvailableKg()).isEqualByComparingTo("25");
            assertThat(trip.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
        }

        @Test
        @DisplayName("kilo libre et colis sans poids : aucun kilo ni statut ne bouge")
        void kgFree_andWeightless_noChange() {
            AnnouncementEntity free = plane("1");
            free.setCapacityUnit(CapacityUnit.KG_FREE);
            assertThat(TripCapacity.reserve(free, parcel("25", null))).isFalse();
            assertThat(free.getAvailableKg()).isEqualByComparingTo("1");

            AnnouncementEntity full = plane("0");
            full.setStatus(AnnouncementStatus.FULL);
            assertThat(TripCapacity.release(full, parcel(null, null))).isFalse();
            assertThat(full.getStatus()).isEqualTo(AnnouncementStatus.FULL);
        }

        @Test
        @DisplayName("bateau AU KILO avec volume facultatif : le volume n'est jamais décrémenté par un colis au kilo")
        void boatPerKg_optionalVolume_untouched() {
            AnnouncementEntity trip = plane("30");
            trip.setTransportMode(TransportMode.BOAT);
            trip.setVolumeCapacityM3(new BigDecimal("2"));
            trip.setAvailableVolumeM3(new BigDecimal("2"));

            // Même un colis portant un volume (il n'en reçoit pas sur un trajet au kilo) ne touche pas au volume.
            TripCapacity.reserve(trip, parcel("5", "0.5"));
            assertThat(trip.getAvailableKg()).isEqualByComparingTo("25");
            assertThat(trip.getAvailableVolumeM3()).isEqualByComparingTo("2");

            TripCapacity.release(trip, parcel("5", "0.5"));
            assertThat(trip.getAvailableKg()).isEqualByComparingTo("30");
            assertThat(trip.getAvailableVolumeM3()).isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("acceptation : poids au-delà des kilos restants → 409 capacity-insufficient ; kilo libre passe")
        void assertAcceptable_kg() {
            assertThatThrownBy(() -> TripCapacity.assertAcceptable(plane("4"), parcel("5", null)))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getErrorCode()).isEqualTo("capacity-insufficient");
                    });
            assertThatCode(() -> TripCapacity.assertAcceptable(plane("5"), parcel("5", null)))
                    .doesNotThrowAnyException();
            assertThatCode(() -> TripCapacity.assertAcceptable(plane("4"), parcel(null, null)))
                    .doesNotThrowAnyException();
            AnnouncementEntity free = plane("1");
            free.setCapacityUnit(CapacityUnit.KG_FREE);
            assertThatCode(() -> TripCapacity.assertAcceptable(free, parcel("25", null))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("réservation : poids au-delà des kilos restants → 422 weight-exceeds-capacity, comme avant")
        void assertWeightBookable_kg() {
            assertThatThrownBy(() -> TripCapacity.assertWeightBookable(plane("4"), new BigDecimal("5")))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.getErrorCode()).isEqualTo("weight-exceeds-capacity");
                    });
            assertThatCode(() -> TripCapacity.assertWeightBookable(plane("5"), new BigDecimal("5")))
                    .doesNotThrowAnyException();
            assertThatCode(() -> TripCapacity.assertWeightBookable(plane("5"), null)).doesNotThrowAnyException();
            AnnouncementEntity free = plane("1");
            free.setCapacityUnit(CapacityUnit.KG_FREE);
            assertThatCode(() -> TripCapacity.assertWeightBookable(free, new BigDecimal("25")))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("trajet ou colis absent : sans effet, sans exception")
    void nulls_areNoOps() {
        assertThat(TripCapacity.reserve(null, parcel("5", null))).isFalse();
        assertThat(TripCapacity.release(plane("5"), null)).isFalse();
        assertThat(TripCapacity.reserve(plane("5"), null)).isFalse();
        assertThat(TripCapacity.release(null, parcel("5", null))).isFalse();
        assertThatCode(() -> TripCapacity.assertAcceptable(null, parcel("5", null))).doesNotThrowAnyException();
        assertThatCode(() -> TripCapacity.assertAcceptable(plane("5"), null)).doesNotThrowAnyException();
        assertThatCode(() -> TripCapacity.assertWeightBookable(null, BigDecimal.TEN)).doesNotThrowAnyException();
    }
}
