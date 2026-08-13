package com.yadony.api.matching;

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
 * Contrat entité pour la devise du bid (Task 7, migration V198) : défaut EUR
 * et round-trip sur toute devise supportée.
 *
 * <p>La contrainte {@code chk_bids_currency} elle-même (rejet des valeurs hors
 * liste) n'est pas vérifiable ici : le profil test désactive Flyway et génère
 * le schéma H2 depuis les annotations JPA ({@code spring.jpa.hibernate.ddl-auto:
 * create}), donc la CHECK constraint SQL n'existe jamais en base de test. Voir
 * {@link com.yadony.api.migrations.PaymentCurrencyMigrationTest} pour
 * l'assertion sur le contenu de la migration V198.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class BidCurrencyMappingTest {

    @Autowired BidRepository bidRepository;
    @Autowired TestEntityManager em;

    private UUID newAnnouncement() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setDepartureDate(LocalDate.of(2026, 8, 15));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Gare du Nord, Paris");
        a.setPickupLat(new BigDecimal("48.880756"));
        a.setPickupLng(new BigDecimal("2.354987"));
        a.setDeliveryAddressLabel("Aéroport Bamako-Sénou");
        a.setDeliveryLat(new BigDecimal("12.533579"));
        a.setDeliveryLng(new BigDecimal("-7.948969"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("23.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setTimezone("Europe/Paris");
        a.setStatus(AnnouncementStatus.COMPLETED);
        return em.persistAndFlush(a).getId();
    }

    private BidEntity newUnsavedBid(UUID announcementId) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcementId);
        b.setSenderId(UUID.randomUUID());
        b.setStatus(BidStatus.PENDING);
        return b;
    }

    @Test
    void defaultsToEurWhenCurrencyNotSet() {
        UUID ann = newAnnouncement();
        BidEntity bid = newUnsavedBid(ann);

        UUID id = em.persistAndFlush(bid).getId();
        em.clear();

        BidEntity reloaded = bidRepository.findById(id).orElseThrow();
        assertThat(reloaded.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void roundTripsAnySupportedCurrency() {
        UUID ann = newAnnouncement();
        BidEntity bid = newUnsavedBid(ann);
        bid.setCurrency("CAD");

        UUID id = em.persistAndFlush(bid).getId();
        em.clear();

        BidEntity reloaded = bidRepository.findById(id).orElseThrow();
        assertThat(reloaded.getCurrency()).isEqualTo("CAD");
    }
}
