package com.yadony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class AnnouncementSpecificationTest {

    @Autowired
    private AnnouncementRepository repository;

    @Test
    void announcementCurrency_defaultsToEur() {
        AnnouncementEntity announcement = new AnnouncementEntity();

        assertThat(announcement.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void hasCurrency_filtersByExactCurrencyWithoutMixingEurAndCad() {
        AnnouncementEntity eurAnnouncement = buildAnnouncement("EUR");
        AnnouncementEntity cadAnnouncement = buildAnnouncement("CAD");
        repository.saveAndFlush(eurAnnouncement);
        repository.saveAndFlush(cadAnnouncement);

        Specification<AnnouncementEntity> spec = AnnouncementSpecification.hasCurrency("CAD");
        List<AnnouncementEntity> results = repository.findAll(spec);

        assertThat(results).extracting(AnnouncementEntity::getId).containsExactly(cadAnnouncement.getId());
        assertThat(results).extracting(AnnouncementEntity::getCurrency).containsExactly("CAD");
    }

    private AnnouncementEntity buildAnnouncement(String currency) {
        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setTravelerId(UUID.randomUUID());
        announcement.setDepartureCity("Paris");
        announcement.setArrivalCity("Dakar");
        announcement.setDepartureDate(LocalDate.now().plusDays(10));
        announcement.setTransportMode(TransportMode.PLANE);
        announcement.setPickupAddressLabel("CDG Terminal 2E");
        announcement.setPickupLat(new BigDecimal("49.009000"));
        announcement.setPickupLng(new BigDecimal("2.547000"));
        announcement.setDeliveryAddressLabel("Aéroport LSS");
        announcement.setDeliveryLat(new BigDecimal("14.739000"));
        announcement.setDeliveryLng(new BigDecimal("-17.490000"));
        announcement.setAvailableKg(new BigDecimal("20.00"));
        announcement.setTotalKg(new BigDecimal("23.00"));
        announcement.setPricePerKg(new BigDecimal("8.00"));
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        announcement.setCurrency(currency);
        return announcement;
    }
}
