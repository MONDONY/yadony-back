package com.yadony.api.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verrou de ligne du trajet (FLUTTER-KV) : l'UPDATE neutre s'exécute sous H2 comme sous
 * PostgreSQL, ne change aucune donnée, et la lecture qui le suit voit la devise commitée.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("AnnouncementRepository.lockForUpdate")
class AnnouncementLockForUpdateDbTest {

    @Autowired private AnnouncementRepository repository;
    @Autowired private TestEntityManager em;

    private UUID announcement(String currency) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Gare du Nord, Paris");
        a.setPickupLat(new BigDecimal("48.880756"));
        a.setPickupLng(new BigDecimal("2.354987"));
        a.setDeliveryAddressLabel("Aéroport LSS");
        a.setDeliveryLat(new BigDecimal("14.739000"));
        a.setDeliveryLng(new BigDecimal("-17.490000"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("20.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setTimezone("Europe/Paris");
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setCurrency(currency);
        UUID id = em.persistAndFlush(a).getId();
        em.clear();
        return id;
    }

    @Test
    void verrouilleUnTrajetExistant_sansRienChanger() {
        UUID id = announcement("XOF");

        assertThat(repository.lockForUpdate(id)).isEqualTo(1);
        assertThat(repository.findById(id)).get()
                .extracting(AnnouncementEntity::getCurrency).isEqualTo("XOF");
    }

    @Test
    void trajetInconnu_zero() {
        assertThat(repository.lockForUpdate(UUID.randomUUID())).isZero();
    }
}
