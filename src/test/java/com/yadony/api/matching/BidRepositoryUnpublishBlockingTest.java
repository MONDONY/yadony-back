package com.yadony.api.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/** Quels colis interdisent de dépublier un trajet (409 announcement/has-bids). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class BidRepositoryUnpublishBlockingTest {

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
        a.setStatus(AnnouncementStatus.ACTIVE);
        return em.persistAndFlush(a).getId();
    }

    private void newBid(UUID announcementId, BidStatus status, boolean hiddenByTraveler) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcementId);
        b.setSenderId(UUID.randomUUID());
        b.setStatus(status);
        b.setWeightKg(new BigDecimal("5"));
        b.setCurrency("EUR");
        b.setDeletedByTraveler(hiddenByTraveler);
        em.persistAndFlush(b);
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"PENDING", "PAYMENT_ESCROWED", "ACCEPTED", "AWAITING_PAYMENT",
            "HANDED_OVER", "IN_TRANSIT", "ARRIVED"})
    void liveStatusBlocks(BidStatus status) {
        UUID ann = newAnnouncement();
        newBid(ann, status, false);
        assertThat(bidRepository.existsUnpublishBlockingByAnnouncementId(ann)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"ACCEPTED", "AWAITING_PAYMENT", "HANDED_OVER", "IN_TRANSIT",
            "ARRIVED"})
    void committedStatusBlocksEvenWhenHiddenByTraveler(BidStatus status) {
        UUID ann = newAnnouncement();
        newBid(ann, status, true);
        assertThat(bidRepository.existsUnpublishBlockingByAnnouncementId(ann)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"PENDING", "PAYMENT_ESCROWED"})
    void requestHiddenByTravelerDoesNotBlock(BidStatus status) {
        UUID ann = newAnnouncement();
        newBid(ann, status, true);
        assertThat(bidRepository.existsUnpublishBlockingByAnnouncementId(ann)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"CANCELLED", "REJECTED", "COMPLETED", "EXPIRED"})
    void deadStatusDoesNotBlock(BidStatus status) {
        UUID ann = newAnnouncement();
        newBid(ann, status, false);
        assertThat(bidRepository.existsUnpublishBlockingByAnnouncementId(ann)).isFalse();
    }

    @Test
    void noBidAndOtherAnnouncementDoNotBlock() {
        UUID ann = newAnnouncement();
        UUID other = newAnnouncement();
        newBid(other, BidStatus.ACCEPTED, false);
        assertThat(bidRepository.existsUnpublishBlockingByAnnouncementId(ann)).isFalse();
    }
}
