package com.yadony.api.tracking;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.TransportMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class TripPositionSampleRepositoryTest {

    @Autowired
    private TripPositionSampleRepository repository;
    @Autowired
    private AnnouncementRepository announcementRepository;

    private AnnouncementEntity trip() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("CDG");
        a.setPickupLat(new BigDecimal("49.009000"));
        a.setPickupLng(new BigDecimal("2.547000"));
        a.setDeliveryAddressLabel("LSS");
        a.setDeliveryLat(new BigDecimal("14.739000"));
        a.setDeliveryLng(new BigDecimal("-17.490000"));
        a.setAvailableKg(new BigDecimal("20"));
        a.setTotalKg(new BigDecimal("20"));
        a.setReservedKg(BigDecimal.ZERO);
        a.setPricePerKg(new BigDecimal("5"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return a;
    }

    @Test
    void savesAndReadsSamplesInOrder() {
        AnnouncementEntity a = trip();
        OffsetDateTime t0 = OffsetDateTime.of(2026, 11, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        a.setDepartedAt(t0);
        a.setDeparturePromptSentAt(t0.minusHours(1));
        a.setArrivalPromptSentAt(t0.plusHours(6));
        a = announcementRepository.saveAndFlush(a);
        UUID id = a.getId();

        repository.saveAndFlush(new TripPositionSampleEntity(id, t0.plusHours(2), "Madrid", "ES", 40, SampleSource.OUVERTURE));
        repository.saveAndFlush(new TripPositionSampleEntity(id, t0, "Paris", "FR", 0, SampleSource.DEPART));
        repository.saveAndFlush(new TripPositionSampleEntity(id, t0.plusHours(9), "Dakar", "SN", null, SampleSource.ARRIVEE));

        List<TripPositionSampleEntity> window = repository
                .findByAnnouncementIdAndCapturedAtBetweenOrderByCapturedAtAsc(id, t0, t0.plusHours(3));
        assertThat(window).extracting(TripPositionSampleEntity::getCityName).containsExactly("Paris", "Madrid");
        assertThat(window.get(1).getProgressPct()).isEqualTo(40);
        assertThat(window.get(1).getSource()).isEqualTo(SampleSource.OUVERTURE);

        assertThat(repository.findFirstByAnnouncementIdOrderByCapturedAtDesc(id))
                .get().extracting(TripPositionSampleEntity::getCityName).isEqualTo("Dakar");

        AnnouncementEntity reloaded = announcementRepository.findById(id).orElseThrow();
        assertThat(reloaded.getDepartedAt()).isEqualTo(t0);
        assertThat(reloaded.getDeparturePromptSentAt()).isEqualTo(t0.minusHours(1));
        assertThat(reloaded.getArrivalPromptSentAt()).isEqualTo(t0.plusHours(6));
    }
}
