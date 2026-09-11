package com.yadony.api.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * Le net « réservé » d'une annonce additionne, pour les colis confirmés, l'accord
 * négocié quand il existe, sinon poids × prix au kilo plus les articles de grille.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class BidRepositoryReservedNetTest {

    private static final List<String> CONFIRMED = List.of("ACCEPTED", "HANDED_OVER", "IN_TRANSIT", "COMPLETED");

    @Autowired BidRepository bidRepository;
    @Autowired TestEntityManager em;

    private UUID newAnnouncement(String pricePerKg) {
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
        a.setPricePerKg(new BigDecimal(pricePerKg));
        a.setTimezone("Europe/Paris");
        a.setStatus(AnnouncementStatus.ACTIVE);
        return em.persistAndFlush(a).getId();
    }

    private BidEntity newBid(UUID announcementId, BidStatus status, String weightKg, String negotiatedNet) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcementId);
        b.setSenderId(UUID.randomUUID());
        b.setStatus(status);
        b.setWeightKg(weightKg == null ? null : new BigDecimal(weightKg));
        b.setNegotiatedNetEur(negotiatedNet == null ? null : new BigDecimal(negotiatedNet));
        b.setCurrency("EUR");
        return em.persistAndFlush(b);
    }

    private void newGridItem(UUID bidId, String unitNet, int quantity) {
        BidGridItemEntity item = new BidGridItemEntity();
        item.setBidId(bidId);
        item.setAnnouncementGridItemId(UUID.randomUUID());
        item.setLabelSnapshot("Valise cabine");
        item.setUnitPriceNetSnapshot(new BigDecimal(unitNet));
        item.setQuantity(quantity);
        em.persistAndFlush(item);
    }

    @Test
    void sumsNegotiatedAgreementsAndPricedParcels_withGridItems() {
        UUID ann = newAnnouncement("8.00");
        newBid(ann, BidStatus.ACCEPTED, "10", "45.00");          // accord négocié : 45
        BidEntity direct = newBid(ann, BidStatus.HANDED_OVER, "3", null); // 3 × 8 = 24
        newGridItem(direct.getId(), "5.00", 2);                   // + 2 × 5 = 10

        BigDecimal reserved = bidRepository.sumReservedNetByAnnouncementId(ann, CONFIRMED);

        assertThat(reserved).isEqualByComparingTo("79.00");
    }

    @Test
    void ignoresPendingRefusedAndDeletedBids() {
        UUID ann = newAnnouncement("8.00");
        newBid(ann, BidStatus.PENDING, "5", null);
        newBid(ann, BidStatus.REJECTED, "5", "40.00");
        BidEntity deleted = newBid(ann, BidStatus.ACCEPTED, "5", "40.00");
        deleted.setDeletedAt(LocalDateTime.now());
        em.persistAndFlush(deleted);

        assertThat(bidRepository.sumReservedNetByAnnouncementId(ann, CONFIRMED)).isEqualByComparingTo("0");
    }

    @Test
    void gridOnlyBid_withoutWeight_countsTheGridItems() {
        // Mode grille : le bid n'a pas de poids, seul le prix des articles compte.
        UUID ann = newAnnouncement("8.00");
        BidEntity gridBid = newBid(ann, BidStatus.COMPLETED, null, null);
        newGridItem(gridBid.getId(), "12.50", 2);

        assertThat(bidRepository.sumReservedNetByAnnouncementId(ann, CONFIRMED)).isEqualByComparingTo("25.00");
    }
}
