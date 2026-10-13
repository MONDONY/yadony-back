package com.yadony.api.auth;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("UserRepository.countCompletedShipments — envois menés à bien (FLUTTER-NV)")
class UserRepositoryCompletedShipmentsDbTest {

    @Autowired private UserRepository repository;
    @Autowired private TestEntityManager em;

    private UUID announcement(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
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
        return em.persistAndFlush(a).getId();
    }

    private void bid(UUID announcementId, UUID senderId, BidStatus status) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcementId);
        b.setSenderId(senderId);
        b.setStatus(status);
        em.persistAndFlush(b);
    }

    @Test
    void comptelesColisLivres_commeExpediteurEtCommeVoyageur() {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID ownTrip = announcement(user);
        UUID otherTrip = announcement(other);

        bid(ownTrip, other, BidStatus.COMPLETED);      // voyageur, livré
        bid(ownTrip, other, BidStatus.COMPLETED);      // voyageur, livré
        bid(ownTrip, other, BidStatus.IN_TRANSIT);     // en cours : ne compte pas
        bid(otherTrip, user, BidStatus.COMPLETED);     // expéditeur, livré
        bid(otherTrip, user, BidStatus.CANCELLED);     // annulé : ne compte pas
        bid(otherTrip, other, BidStatus.COMPLETED);    // étranger à l'utilisateur

        assertThat(repository.countCompletedShipments(user)).isEqualTo(3);
        assertThat(repository.countCompletedShipments(UUID.randomUUID())).isZero();
    }
}
