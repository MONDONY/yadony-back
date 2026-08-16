package com.yadony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class BidRepositoryTest {

    @Autowired private BidRepository bidRepository;
    @Autowired private TestEntityManager em;

    private UUID newAnnouncement(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
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

    private void newBid(UUID announcementId, BidStatus status) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcementId);
        b.setSenderId(UUID.randomUUID());
        b.setStatus(status);
        em.persistAndFlush(b);
    }

    @Test
    void findByAnnouncementIdAndStatusNotIn_excludesTerminalStatuses() {
        // Given: un trajet avec un bid IN_TRANSIT et un bid CANCELLED
        UUID announcementId = seedAnnouncementWithTwoBids();

        List<BidEntity> active = bidRepository.findByAnnouncementIdAndStatusNotIn(
                announcementId, Set.of(BidStatus.CANCELLED, BidStatus.REJECTED));

        assertThat(active).hasSize(1);
        assertThat(active.get(0).getStatus()).isEqualTo(BidStatus.IN_TRANSIT);
    }

    private UUID seedAnnouncementWithTwoBids() {
        UUID traveler = UUID.randomUUID();
        UUID announcementId = newAnnouncement(traveler);
        newBid(announcementId, BidStatus.IN_TRANSIT);
        newBid(announcementId, BidStatus.CANCELLED);
        return announcementId;
    }
}
