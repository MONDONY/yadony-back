package com.yadony.api.payments.amendment;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.TransportMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Volume d'un ajout sur un trajet vendu au m³ : retiré à la création, rendu une seule fois. */
@ExtendWith(MockitoExtension.class)
class BidAmendmentCapacityVolumeTest {

    @Mock AnnouncementRepository announcements;
    @Mock BidAmendmentRepository amendments;
    @Mock CacheManager cacheManager;

    private BidAmendmentCapacity capacity;
    private AnnouncementEntity trip;

    @BeforeEach
    void setUp() {
        capacity = new BidAmendmentCapacity(announcements, amendments, cacheManager);
        trip = new AnnouncementEntity();
        BidAmendmentTestSupport.setId(trip, UUID.randomUUID());
        trip.setTransportMode(TransportMode.BOAT);
        trip.setPricePerM3(new BigDecimal("80.00"));
        trip.setVolumeCapacityM3(new BigDecimal("2.500"));
        trip.setAvailableVolumeM3(new BigDecimal("1.000"));
        trip.setCapacityUnit(CapacityUnit.KG_EXACT);
        trip.setStatus(AnnouncementStatus.ACTIVE);
    }

    @Test
    void isAvailable_volume() {
        assertThat(BidAmendmentCapacity.isAvailable(trip, null, new BigDecimal("1.000"))).isTrue();
        assertThat(BidAmendmentCapacity.isAvailable(trip, null, new BigDecimal("1.001"))).isFalse();
        assertThat(BidAmendmentCapacity.isAvailable(trip, null, null)).isTrue();
        trip.setAvailableVolumeM3(null);
        assertThat(BidAmendmentCapacity.isAvailable(trip, null, new BigDecimal("0.1"))).isFalse();
    }

    @Test
    void isAvailable_volumeOnKgTrip_ignored() {
        AnnouncementEntity kgTrip = new AnnouncementEntity();
        kgTrip.setTransportMode(TransportMode.PLANE);
        kgTrip.setPricePerKg(new BigDecimal("10"));
        kgTrip.setCapacityUnit(CapacityUnit.KG_EXACT);
        kgTrip.setAvailableKg(new BigDecimal("5"));

        assertThat(BidAmendmentCapacity.isAvailable(kgTrip, BigDecimal.ZERO, new BigDecimal("9"))).isTrue();
    }

    @Test
    void requireAvailable_volumeAboveAvailable_409() {
        assertThatThrownBy(() -> BidAmendmentCapacity.requireAvailable(trip, BigDecimal.ZERO, new BigDecimal("1.5")))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("amendment-capacity-unavailable");
                });
    }

    @Test
    void reserve_thenRelease_volumeOnce() {
        BidAmendmentEntity a = volumeAmendment("1.000");
        when(announcements.findByIdForUpdate(trip.getId())).thenReturn(Optional.of(trip));

        capacity.reserve(a, trip);

        assertThat(trip.getAvailableVolumeM3()).isEqualByComparingTo("0");
        assertThat(trip.getStatus()).isEqualTo(AnnouncementStatus.FULL);
        assertThat(a.getCapacityReservedVolumeM3()).isEqualByComparingTo("1.000");
        assertThat(a.getCapacityReservedKg()).isEqualByComparingTo("0");
        assertThat(BidAmendmentCapacity.holdsCapacity(a)).isTrue();

        capacity.release(a);
        capacity.release(a);

        assertThat(trip.getAvailableVolumeM3()).isEqualByComparingTo("1.000");
        assertThat(trip.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
        assertThat(a.getCapacityReservedVolumeM3()).isEqualByComparingTo("0");
        assertThat(BidAmendmentCapacity.holdsCapacity(a)).isFalse();
    }

    @Test
    void release_neverAboveOfferedVolume() {
        BidAmendmentEntity a = volumeAmendment("1.000");
        a.setCapacityReservedVolumeM3(new BigDecimal("1.000"));
        trip.setAvailableVolumeM3(new BigDecimal("2.000"));
        when(announcements.findByIdForUpdate(trip.getId())).thenReturn(Optional.of(trip));

        capacity.release(a);

        assertThat(trip.getAvailableVolumeM3()).isEqualByComparingTo("2.500");
    }

    @Test
    void reserve_noVolumeNoKg_touchesNothing() {
        BidAmendmentEntity a = volumeAmendment(null);

        capacity.reserve(a, trip);

        assertThat(trip.getAvailableVolumeM3()).isEqualByComparingTo("1.000");
        verify(amendments, never()).save(any());
        verify(announcements, never()).save(any());
    }

    private BidAmendmentEntity volumeAmendment(String volume) {
        BidAmendmentEntity a = new BidAmendmentEntity();
        BidAmendmentTestSupport.setId(a, UUID.randomUUID());
        a.setAnnouncementId(trip.getId());
        a.setAddedVolumeM3(volume == null ? null : new BigDecimal(volume));
        return a;
    }
}
