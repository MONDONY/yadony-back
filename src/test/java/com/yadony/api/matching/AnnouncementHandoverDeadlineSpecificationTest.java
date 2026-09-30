package com.yadony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AnnouncementSpecification#handoverDeadlineNotPassed} : un trajet dont la date
 * limite de remise est passée n'apparaît plus en recherche (FLUTTER-46).
 */
@DataJpaTest
@ActiveProfiles("test")
class AnnouncementHandoverDeadlineSpecificationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 30, 10, 0);

    @Autowired
    private AnnouncementRepository repository;

    private AnnouncementEntity persist(LocalDateTime handoverDeadline) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("CDG Terminal 2E");
        a.setPickupLat(new BigDecimal("49.009000"));
        a.setPickupLng(new BigDecimal("2.547000"));
        a.setDeliveryAddressLabel("Aéroport LSS");
        a.setDeliveryLat(new BigDecimal("14.739000"));
        a.setDeliveryLng(new BigDecimal("-17.490000"));
        a.setAvailableKg(new BigDecimal("20"));
        a.setTotalKg(new BigDecimal("20"));
        a.setPricePerKg(new BigDecimal("5"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setHandoverDeadline(handoverDeadline);
        return repository.saveAndFlush(a);
    }

    private List<UUID> search() {
        Specification<AnnouncementEntity> spec = AnnouncementSpecification.handoverDeadlineNotPassed(NOW);
        return repository.findAll(spec).stream().map(AnnouncementEntity::getId).toList();
    }

    @Test
    void excludesTripWhoseHandoverDeadlinePassed() {
        AnnouncementEntity expired = persist(NOW.minusDays(1));
        assertThat(search()).doesNotContain(expired.getId());
    }

    @Test
    void excludesTripWhoseHandoverDeadlineIsNow() {
        AnnouncementEntity atDeadline = persist(NOW);
        assertThat(search()).doesNotContain(atDeadline.getId());
    }

    @Test
    void includesTripWithFutureHandoverDeadline() {
        AnnouncementEntity open = persist(NOW.plusHours(2));
        assertThat(search()).contains(open.getId());
    }

    @Test
    void includesTripWithoutHandoverDeadline() {
        AnnouncementEntity legacy = persist(null);
        assertThat(search()).contains(legacy.getId());
    }
}
