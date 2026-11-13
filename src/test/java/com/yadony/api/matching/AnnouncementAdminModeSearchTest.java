package com.yadony.api.matching;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recherche du back-office ({@link AnnouncementRepository#searchAdminByModes}) exécutée contre la base :
 * le filtre de mode se combine en ET avec la recherche par ville, la route couvrant voiture et bus.
 */
@DataJpaTest
@ActiveProfiles("test")
class AnnouncementAdminModeSearchTest {

    @Autowired
    private AnnouncementRepository repository;

    private UUID boatDakar;
    private UUID busDakar;
    private UUID carBamako;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        boatDakar = persist(TransportMode.BOAT, "Dakar");
        persist(TransportMode.PLANE, "Dakar");
        busDakar = persist(TransportMode.BUS, "Dakar");
        carBamako = persist(TransportMode.CAR, "Bamako");
    }

    @Test
    void filterCombinesWithQuery_onlyTheBoatToDakar() {
        assertThat(search("%dakar%", List.of(TransportMode.BOAT))).containsExactly(boatDakar);
    }

    @Test
    void roadFilterReturnsCarAndBus_thenNarrowedByQuery() {
        List<TransportMode> road = TransportMode.acceptedBy(TransportMode.CAR);
        assertThat(search("%dakar%", road)).containsExactly(busDakar);
        assertThat(search("%a%", road)).containsExactlyInAnyOrder(busDakar, carBamako);
    }

    private List<UUID> search(String like, List<TransportMode> modes) {
        return repository.searchAdminByModes(like, modes, PageRequest.of(0, 20)).stream()
                .map(AnnouncementEntity::getId).toList();
    }

    private UUID persist(TransportMode mode, String arrival) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity("Paris");
        a.setArrivalCity(arrival);
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(mode);
        a.setPickupAddressLabel("Départ");
        a.setPickupLat(new BigDecimal("48.838000"));
        a.setPickupLng(new BigDecimal("2.382000"));
        a.setDeliveryAddressLabel("Arrivée");
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
