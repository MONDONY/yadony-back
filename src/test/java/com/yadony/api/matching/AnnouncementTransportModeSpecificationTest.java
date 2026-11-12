package com.yadony.api.matching;

import org.junit.jupiter.api.BeforeEach;
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

/**
 * {@link AnnouncementSpecification#hasTransportMode} exécutée contre la base : la route couvre
 * la voiture et le bus (même règle que {@link TransportMode#accepts}), les autres modes restent
 * en égalité stricte.
 */
@DataJpaTest
@ActiveProfiles("test")
class AnnouncementTransportModeSpecificationTest {

    @Autowired
    private AnnouncementRepository repository;

    private UUID car;
    private UUID bus;
    private UUID plane;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        car = persist(TransportMode.CAR);
        bus = persist(TransportMode.BUS);
        plane = persist(TransportMode.PLANE);
    }

    @Test
    void carFilterAlsoReturnsBusTrips() {
        assertThat(search(AnnouncementSpecification.hasTransportMode(TransportMode.CAR)))
                .containsExactlyInAnyOrder(car, bus);
    }

    @Test
    void busFilterAlsoReturnsCarTrips() {
        assertThat(search(AnnouncementSpecification.hasTransportMode(TransportMode.BUS)))
                .containsExactlyInAnyOrder(car, bus);
    }

    @Test
    void planeFilterIsStrict() {
        assertThat(search(AnnouncementSpecification.hasTransportMode(TransportMode.PLANE)))
                .containsExactly(plane);
    }

    @Test
    void boatFilterIsStrict() {
        assertThat(search(AnnouncementSpecification.hasTransportMode(TransportMode.BOAT))).isEmpty();
    }

    @Test
    void carFilter_countMatchesContent_andCombinesAsAnd() {
        Specification<AnnouncementEntity> spec = AnnouncementSpecification.hasTransportMode(TransportMode.CAR);
        assertThat(repository.count(spec)).isEqualTo(2);
        assertThat(search(spec.and(AnnouncementSpecification.hasArrivalCity("Bamako")))).isEmpty();
    }

    private List<UUID> search(Specification<AnnouncementEntity> spec) {
        return repository.findAll(spec).stream().map(AnnouncementEntity::getId).toList();
    }

    private UUID persist(TransportMode mode) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(mode);
        a.setPickupAddressLabel("Gare routière Bercy");
        a.setPickupLat(new BigDecimal("48.838000"));
        a.setPickupLng(new BigDecimal("2.382000"));
        a.setDeliveryAddressLabel("Gare routière Dakar");
        a.setDeliveryLat(new BigDecimal("14.739000"));
        a.setDeliveryLng(new BigDecimal("-17.490000"));
        a.setCapacityUnit(CapacityUnit.KG_EXACT);
        a.setAvailableKg(new BigDecimal("10"));
        a.setTotalKg(new BigDecimal("10"));
        a.setPricePerKg(new BigDecimal("5"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return repository.saveAndFlush(a).getId();
    }
}
