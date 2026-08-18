package com.yadony.api.matching;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce que devient un fil de négociation une fois qu'il n'est plus ouvert : accord
 * conclu ou fil clos. Tout est joué sur les vrais services et le vrai schéma —
 * les défauts visés naissent justement de la rencontre entre du code de
 * négociation et des requêtes écrites avant qu'elle n'existe.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Fil de négociation — ce qu'il devient après l'accord ou la fermeture")
class BidNegotiationLifecycleIntegrationTest {

    @Autowired private BidNegotiationService negotiationService;
    @Autowired private BidTimeoutScheduler bidTimeoutScheduler;
    @Autowired private BidRepository bidRepository;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private BidNegotiationMessageRepository messageRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanDb() {
        messageRepository.deleteAll();
        bidRepository.deleteAll();
        announcementRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ── D1 : un accord en espèces ne doit pas être ramassé par le timeout ─────

    @Test
    @DisplayName("un accord en espèces conclu sur un fil de plus de 24 h survit au timeout des demandes sans réponse")
    void cashAgreementOnAnOldThread_isNotAutoCancelledByTheTimeout() {
        UserEntity sender = persistUser("uid-nego-sender-" + UUID.randomUUID());
        UserEntity traveler = persistUser("uid-nego-traveler-" + UUID.randomUUID());
        AnnouncementEntity announcement = persistNegotiableAnnouncement(traveler.getId());
        BidEntity thread = persistNegotiatingBid(announcement.getId(), sender.getId());
        persistProposal(thread.getId(), sender.getId());

        // Cas NOMINAL : la fenêtre d'inactivité d'un fil est de 72 h, un fil de
        // 30 h est parfaitement vivant au moment où le voyageur accepte le prix.
        backdateBidCreation(thread.getId(), LocalDateTime.now(ZoneOffset.UTC).minusHours(30));

        negotiationService.accept(thread.getId(), traveler.getFirebaseUid());
        assertThat(bidRepository.findById(thread.getId()).orElseThrow().getStatus())
                .isEqualTo(BidStatus.PENDING);

        bidTimeoutScheduler.autoCancelUnansweredBids();

        BidEntity after = bidRepository.findById(thread.getId()).orElseThrow();
        assertThat(after.getStatus())
                .describedAs("l'accord vient d'être conclu : le voyageur n'a pas encore eu "
                        + "le temps de régler la commission, le timeout ne doit pas le détruire")
                .isEqualTo(BidStatus.PENDING);
        assertThat(after.getRejectionReason()).isNull();
    }

    @Test
    @DisplayName("une demande ferme restée sans réponse plus de 24 h est bien annulée")
    void plainPendingBidOlderThan24h_isStillAutoCancelled() {
        UserEntity sender = persistUser("uid-timeout-sender-" + UUID.randomUUID());
        UserEntity traveler = persistUser("uid-timeout-traveler-" + UUID.randomUUID());
        AnnouncementEntity announcement = persistNegotiableAnnouncement(traveler.getId());

        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("5.00"));
        bid.setStatus(BidStatus.PENDING);
        BidEntity saved = bidRepository.save(bid);
        backdateBidCreation(saved.getId(), LocalDateTime.now(ZoneOffset.UTC).minusHours(30));

        bidTimeoutScheduler.autoCancelUnansweredBids();

        BidEntity after = bidRepository.findById(saved.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(after.getRejectionReason()).isEqualTo("TRAVELER_NO_RESPONSE");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UserEntity persistUser(String firebaseUid) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(firebaseUid);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.VERIFIED);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.TRAVELER);
        roles.add(Role.SENDER);
        u.setRoles(roles);
        return userRepository.save(u);
    }

    private AnnouncementEntity persistNegotiableAnnouncement(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar Centre");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setNegotiable(true);
        return announcementRepository.save(a);
    }

    private BidEntity persistNegotiatingBid(UUID announcementId, UUID senderId) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(senderId);
        bid.setWeightKg(new BigDecimal("5.00"));
        bid.setStatus(BidStatus.NEGOTIATING);
        bid.setNegotiationRound(1);
        bid.setCommissionRate(new BigDecimal("0.05"));
        bid.setPaymentMethod(PaymentMethod.CASH);
        return bidRepository.save(bid);
    }

    private void persistProposal(UUID bidId, UUID authorId) {
        messageRepository.save(BidNegotiationMessageEntity.create(
                bidId, authorId, BidNegotiationMessageKind.PROPOSAL,
                new BigDecimal("45.00"), null));
    }

    /** {@code created_at} est {@code updatable = false} : seul du SQL natif peut vieillir un bid. */
    private void backdateBidCreation(UUID bidId, LocalDateTime when) {
        transactionTemplate.executeWithoutResult(tx -> entityManager
                .createNativeQuery("UPDATE bids SET created_at = :ts WHERE id = :id")
                .setParameter("ts", Timestamp.valueOf(when))
                .setParameter("id", bidId)
                .executeUpdate());
    }
}
