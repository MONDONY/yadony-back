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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("BidRepository — requêtes du fil de négociation")
class BidNegotiationRepositoryTest {

    @Autowired private BidRepository bidRepository;
    @Autowired private TestEntityManager em;

    private UUID newAnnouncement(UUID travelerId, LocalDate departureDate) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(departureDate);
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
        a.setNegotiable(true);
        return em.persistAndFlush(a).getId();
    }

    private UUID newNegotiatingBid(UUID announcementId, UUID senderId) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcementId);
        b.setSenderId(senderId);
        b.setStatus(BidStatus.NEGOTIATING);
        b.setNegotiationRound(1);
        return em.persistAndFlush(b).getId();
    }

    private void newMessage(UUID bidId, UUID authorId) {
        em.persistAndFlush(BidNegotiationMessageEntity.create(
                bidId, authorId, BidNegotiationMessageKind.PROPOSAL,
                new BigDecimal("45.00"), null));
    }

    private void backdateMessages(UUID bidId, LocalDateTime when) {
        em.getEntityManager()
                .createNativeQuery("UPDATE bid_negotiation_messages SET created_at = :ts WHERE bid_id = :bid")
                .setParameter("ts", when)
                .setParameter("bid", bidId)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("un fil consulté récemment mais sans message depuis le seuil reste inactif")
    void staleNegotiation_isDetectedFromLastMessage_notFromBidUpdate() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID announcementId = newAnnouncement(travelerId, LocalDate.now().plusDays(10));
        UUID bidId = newNegotiatingBid(announcementId, senderId);
        newMessage(bidId, senderId);

        // Dernier message il y a 5 h, mais le bid vient d'être écrit (lecture du fil).
        backdateMessages(bidId, LocalDateTime.now(ZoneOffset.UTC).minusHours(5));
        BidEntity bid = em.find(BidEntity.class, bidId);
        bid.setSenderLastReadAt(LocalDateTime.now(ZoneOffset.UTC));
        em.persistAndFlush(bid);

        List<BidEntity> stale = bidRepository.findStaleNegotiations(
                LocalDateTime.now(ZoneOffset.UTC).minusHours(1));

        assertThat(stale).extracting(BidEntity::getId).containsExactly(bidId);
    }

    @Test
    @DisplayName("un fil dont le dernier message est récent n'est pas inactif")
    void freshNegotiation_isNotStale() {
        UUID announcementId = newAnnouncement(UUID.randomUUID(), LocalDate.now().plusDays(10));
        UUID senderId = UUID.randomUUID();
        UUID bidId = newNegotiatingBid(announcementId, senderId);
        newMessage(bidId, senderId);

        List<BidEntity> stale = bidRepository.findStaleNegotiations(
                LocalDateTime.now(ZoneOffset.UTC).minusHours(1));

        assertThat(stale).extracting(BidEntity::getId).doesNotContain(bidId);
    }

    @Test
    @DisplayName("un fil sur un trajet déjà parti est remonté quel que soit son âge")
    void departedTripNegotiation_isReturned() {
        UUID senderId = UUID.randomUUID();
        UUID departed = newAnnouncement(UUID.randomUUID(), LocalDate.now().minusDays(1));
        UUID upcoming = newAnnouncement(UUID.randomUUID(), LocalDate.now().plusDays(10));
        UUID departedBid = newNegotiatingBid(departed, senderId);
        UUID upcomingBid = newNegotiatingBid(upcoming, senderId);

        List<UUID> ids = bidRepository.findNegotiationsOnDepartedTrips(LocalDate.now())
                .stream().map(BidEntity::getId).toList();

        assertThat(ids).contains(departedBid).doesNotContain(upcomingBid);
    }

    @Test
    @DisplayName("les fils sont visibles des deux côtés, expéditeur comme voyageur")
    void negotiationsAreVisibleToBothParties() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID announcementId = newAnnouncement(travelerId, LocalDate.now().plusDays(10));
        UUID bidId = newNegotiatingBid(announcementId, senderId);

        assertThat(bidRepository.findNegotiationsForUser(senderId))
                .extracting(BidEntity::getId).containsExactly(bidId);
        assertThat(bidRepository.findNegotiationsForUser(travelerId))
                .extracting(BidEntity::getId).containsExactly(bidId);
        assertThat(bidRepository.findNegotiationsForUser(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("un fil clos ne remonte plus dans aucune de ces requêtes")
    void closedThreadIsNeverReturned() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID announcementId = newAnnouncement(travelerId, LocalDate.now().minusDays(1));
        UUID bidId = newNegotiatingBid(announcementId, senderId);
        newMessage(bidId, senderId);
        backdateMessages(bidId, LocalDateTime.now(ZoneOffset.UTC).minusHours(5));

        BidEntity bid = em.find(BidEntity.class, bidId);
        bid.setStatus(BidStatus.EXPIRED);
        em.persistAndFlush(bid);

        assertThat(bidRepository.findStaleNegotiations(
                LocalDateTime.now(ZoneOffset.UTC).minusHours(1))).isEmpty();
        assertThat(bidRepository.findNegotiationsOnDepartedTrips(LocalDate.now())).isEmpty();
        assertThat(bidRepository.findNegotiationsForUser(senderId)).isEmpty();
    }
}
