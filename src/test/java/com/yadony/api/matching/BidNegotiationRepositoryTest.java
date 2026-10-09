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
    @Autowired private BidNegotiationMessageRepository messageRepository;
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

    private UUID newBid(UUID announcementId, UUID senderId, BidStatus status, boolean negotiated) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcementId);
        b.setSenderId(senderId);
        b.setStatus(status);
        b.setNegotiationRound(negotiated ? 2 : 0);
        if (negotiated) {
            b.setNegotiatedGrossEur(new BigDecimal("45.00"));
            b.setNegotiatedNetEur(new BigDecimal("42.86"));
        }
        return em.persistAndFlush(b).getId();
    }

    @Test
    @DisplayName("un accord négocié reste listé tant que l'expéditeur n'a pas payé (carte ou espèces)")
    void agreedNegotiationStaysListedUntilPaid() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID announcementId = newAnnouncement(travelerId, LocalDate.now().plusDays(10));
        UUID cardAgreement = newBid(announcementId, senderId, BidStatus.AWAITING_PAYMENT, true);
        UUID cashAgreement = newBid(announcementId, UUID.randomUUID(), BidStatus.PENDING, true);

        // Sans cela, l'acceptation du voyageur faisait disparaître le fil de
        // « Discussions de prix » sans laisser à l'expéditeur de chemin vers le paiement.
        assertThat(bidRepository.findNegotiationsForUser(travelerId))
                .extracting(BidEntity::getId).containsExactlyInAnyOrder(cardAgreement, cashAgreement);
        assertThat(bidRepository.findNegotiationsForUser(senderId))
                .extracting(BidEntity::getId).containsExactly(cardAgreement);
    }

    @Test
    @DisplayName("une demande ferme en attente de paiement n'est pas une discussion de prix")
    void firmBidAwaitingPaymentIsNotANegotiation() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID announcementId = newAnnouncement(travelerId, LocalDate.now().plusDays(10));
        newBid(announcementId, senderId, BidStatus.AWAITING_PAYMENT, false);
        newBid(announcementId, UUID.randomUUID(), BidStatus.PENDING, false);

        assertThat(bidRepository.findNegotiationsForUser(travelerId)).isEmpty();
        assertThat(bidRepository.findNegotiationsForUser(senderId)).isEmpty();
    }

    @Test
    @DisplayName("FLUTTER-HM : un fil conclu, réglé ou clos reste listé des deux côtés, quel que soit son statut")
    void settledOrClosedNegotiationStaysListed() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID announcementId = newAnnouncement(travelerId, LocalDate.now().plusDays(10));
        UUID escrowed = newBid(announcementId, senderId, BidStatus.PAYMENT_ESCROWED, true);
        UUID accepted = newBid(announcementId, senderId, BidStatus.ACCEPTED, true);
        UUID handedOver = newBid(announcementId, senderId, BidStatus.HANDED_OVER, true);
        UUID arrived = newBid(announcementId, senderId, BidStatus.ARRIVED, true);
        UUID completed = newBid(announcementId, senderId, BidStatus.COMPLETED, true);
        UUID closedAfterAgreement = newBid(announcementId, senderId, BidStatus.NEGOTIATION_CLOSED, true);
        UUID closedWithoutAgreement = newBid(announcementId, senderId, BidStatus.NEGOTIATION_CLOSED, false);

        // Avant : le fil disparaissait de « Discussions de prix » (Toutes, Terminées et
        // Archivées) dès le règlement, sans que l'utilisateur ait rien rangé ni retiré.
        UUID[] all = {escrowed, accepted, handedOver, arrived, completed,
                closedAfterAgreement, closedWithoutAgreement};
        assertThat(bidRepository.findNegotiationsForUser(senderId))
                .extracting(BidEntity::getId).containsExactlyInAnyOrder(all);
        assertThat(bidRepository.findNegotiationsForUser(travelerId))
                .extracting(BidEntity::getId).containsExactlyInAnyOrder(all);
    }

    @Test
    @DisplayName("une demande jamais négociée n'est jamais listée, quel que soit son statut")
    void neverNegotiatedBid_isNeverListed() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID announcementId = newAnnouncement(travelerId, LocalDate.now().plusDays(10));
        for (BidStatus status : List.of(BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT,
                BidStatus.ARRIVED, BidStatus.COMPLETED, BidStatus.PAYMENT_ESCROWED, BidStatus.CANCELLED)) {
            newBid(announcementId, senderId, status, false);
        }

        assertThat(bidRepository.findNegotiationsForUser(senderId)).isEmpty();
        assertThat(bidRepository.findNegotiationsForUser(travelerId)).isEmpty();
    }

    @Test
    @DisplayName("un fil réglé rangé par le voyageur quitte sa liste courante et passe dans Archivées")
    void settledNegotiationArchivedByTraveler_movesToArchived() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID announcementId = newAnnouncement(travelerId, LocalDate.now().plusDays(10));
        UUID bidId = newBid(announcementId, senderId, BidStatus.HANDED_OVER, true);

        bidRepository.updateNegotiationTravelerArchivedAt(bidId, LocalDateTime.now(ZoneOffset.UTC));

        assertThat(bidRepository.findNegotiationsForUser(travelerId)).isEmpty();
        assertThat(bidRepository.findArchivedNegotiationsForUser(travelerId))
                .extracting(BidEntity::getId).containsExactly(bidId);
        assertThat(bidRepository.findNegotiationsForUser(senderId))
                .extracting(BidEntity::getId).containsExactly(bidId);
    }

    @Test
    @DisplayName("un fil réglé retiré par l'expéditeur n'apparaît plus dans aucune de ses listes")
    void settledNegotiationHiddenBySender_leavesBothLists() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID announcementId = newAnnouncement(travelerId, LocalDate.now().plusDays(10));
        UUID bidId = newBid(announcementId, senderId, BidStatus.ARRIVED, true);

        bidRepository.updateNegotiationSenderHiddenAt(bidId, LocalDateTime.now(ZoneOffset.UTC));

        assertThat(bidRepository.findNegotiationsForUser(senderId)).isEmpty();
        assertThat(bidRepository.findArchivedNegotiationsForUser(senderId)).isEmpty();
        assertThat(bidRepository.findNegotiationsForUser(travelerId))
                .extracting(BidEntity::getId).containsExactly(bidId);
    }

    @Test
    @DisplayName("un fil soft-deleted n'est jamais listé")
    void softDeletedNegotiation_isNeverListed() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID announcementId = newAnnouncement(travelerId, LocalDate.now().plusDays(10));
        UUID bidId = newBid(announcementId, senderId, BidStatus.COMPLETED, true);
        BidEntity bid = em.find(BidEntity.class, bidId);
        bid.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
        em.persistAndFlush(bid);

        assertThat(bidRepository.findNegotiationsForUser(senderId)).isEmpty();
    }

    @Test
    @DisplayName("le dernier message de chaque fil remonte en une requête")
    void latestMessages_areBatchedPerThread() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID announcementId = newAnnouncement(travelerId, LocalDate.now().plusDays(10));
        UUID first = newNegotiatingBid(announcementId, senderId);
        UUID second = newNegotiatingBid(announcementId, UUID.randomUUID());
        UUID silent = newNegotiatingBid(announcementId, UUID.randomUUID());
        newMessage(first, senderId);
        backdateMessages(first, LocalDateTime.now(ZoneOffset.UTC).minusHours(2));
        em.persistAndFlush(BidNegotiationMessageEntity.create(
                first, travelerId, BidNegotiationMessageKind.ACCEPT, new BigDecimal("40.00"), null));
        newMessage(second, travelerId);

        List<BidNegotiationMessageEntity> latest =
                messageRepository.findLatestByBidIdIn(List.of(first, second, silent));

        assertThat(latest).extracting(BidNegotiationMessageEntity::getBidId)
                .containsExactlyInAnyOrder(first, second);
        assertThat(latest).filteredOn(m -> m.getBidId().equals(first)).singleElement()
                .extracting(BidNegotiationMessageEntity::getKind).isEqualTo(BidNegotiationMessageKind.ACCEPT);
    }

    // ─── Rangement / retrait par participant (FLUTTER-EJ, V293) ────────────────

    @Test
    @DisplayName("une discussion rangée par l'expéditeur quitte SA liste, pas celle du voyageur")
    void archivedBySender_leavesOnlyHisList() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID announcementId = newAnnouncement(travelerId, LocalDate.now().plusDays(10));
        UUID bidId = newNegotiatingBid(announcementId, senderId);

        assertThat(bidRepository.updateNegotiationSenderArchivedAt(bidId, LocalDateTime.now(ZoneOffset.UTC)))
                .isEqualTo(1);

        assertThat(bidRepository.findNegotiationsForUser(senderId)).isEmpty();
        assertThat(bidRepository.findNegotiationsForUser(travelerId))
                .extracting(BidEntity::getId).containsExactly(bidId);
        assertThat(bidRepository.findArchivedNegotiationsForUser(senderId))
                .extracting(BidEntity::getId).containsExactly(bidId);
        assertThat(bidRepository.findArchivedNegotiationsForUser(travelerId)).isEmpty();
    }

    @Test
    @DisplayName("le filtre « Archivées » montre les discussions closes et les accords réglés, jamais une offre ferme")
    void archivedList_coversClosedAndSettledNegotiations() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID announcementId = newAnnouncement(travelerId, LocalDate.now().plusDays(10));
        UUID closed = newBid(announcementId, senderId, BidStatus.NEGOTIATION_CLOSED, false);
        UUID settled = newBid(announcementId, senderId, BidStatus.COMPLETED, true);
        UUID firm = newBid(announcementId, senderId, BidStatus.COMPLETED, false);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        bidRepository.updateNegotiationTravelerArchivedAt(closed, now);
        bidRepository.updateNegotiationTravelerArchivedAt(settled, now);
        bidRepository.updateNegotiationTravelerArchivedAt(firm, now);

        assertThat(bidRepository.findArchivedNegotiationsForUser(travelerId))
                .extracting(BidEntity::getId).containsExactlyInAnyOrder(closed, settled);
    }

    @Test
    @DisplayName("une discussion retirée disparaît des deux listes de l'appelant, sans DELETE ni soft delete")
    void hiddenNegotiation_leavesBothListsOfCallerOnly() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID announcementId = newAnnouncement(travelerId, LocalDate.now().plusDays(10));
        UUID bidId = newNegotiatingBid(announcementId, senderId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        bidRepository.updateNegotiationTravelerArchivedAt(bidId, now);
        bidRepository.updateNegotiationTravelerHiddenAt(bidId, now);

        assertThat(bidRepository.findNegotiationsForUser(travelerId)).isEmpty();
        assertThat(bidRepository.findArchivedNegotiationsForUser(travelerId)).isEmpty();
        assertThat(bidRepository.findNegotiationsForUser(senderId))
                .extracting(BidEntity::getId).containsExactly(bidId);

        BidEntity stored = bidRepository.findById(bidId).orElseThrow();
        assertThat(stored.getDeletedAt()).isNull();
        assertThat(stored.isNegotiationHiddenBy(true)).isTrue();
        assertThat(stored.isNegotiationHiddenBy(false)).isFalse();
        assertThat(stored.isNegotiationArchivedBy(true)).isTrue();
    }

    @Test
    @DisplayName("ranger puis désarchiver ne touche pas updated_at, et un save() périmé n'écrase rien")
    void archiveUpdates_leaveUpdatedAtAndSurviveStaleSave() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID announcementId = newAnnouncement(travelerId, LocalDate.now().plusDays(10));
        UUID bidId = newBid(announcementId, senderId, BidStatus.NEGOTIATION_CLOSED, false);
        em.clear();
        BidEntity stale = bidRepository.findById(bidId).orElseThrow();
        LocalDateTime updatedBefore = stale.getUpdatedAt();
        em.detach(stale);

        bidRepository.updateNegotiationSenderArchivedAt(bidId, LocalDateTime.now(ZoneOffset.UTC));
        BidEntity archived = bidRepository.findById(bidId).orElseThrow();
        assertThat(archived.getUpdatedAt()).isEqualTo(updatedBefore);
        assertThat(archived.isNegotiationArchivedBy(false)).isTrue();
        em.detach(archived);

        stale.setDescription("copie périmée sauvegardée par un autre flux");
        bidRepository.saveAndFlush(stale);
        em.clear();

        assertThat(bidRepository.findById(bidId).orElseThrow().isNegotiationArchivedBy(false)).isTrue();

        bidRepository.updateNegotiationSenderArchivedAt(bidId, null);
        assertThat(bidRepository.findById(bidId).orElseThrow().isNegotiationArchivedBy(false)).isFalse();
    }
}
