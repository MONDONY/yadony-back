package com.yadony.api.payments.hold;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserService;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.kyc.events.UserKycRevokedEvent;
import com.yadony.api.kyc.events.UserKycVerifiedEvent;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parcours complet sur base reelle (H2) : bannissement et revocation gelent, leurs levees
 * degelent, les deux motifs se cumulent, et les requetes de la file admin retrouvent les
 * paiements retenus du voyageur.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PayoutHoldIntegrationTest {

    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired com.yadony.api.admin.AdminPaymentInsights adminPaymentInsights;
    @Autowired PayoutHoldService holds;
    @Autowired ApplicationEventPublisher events;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private UserEntity traveler;
    private BidEntity bid;
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        traveler = persistUser("uid-hold-traveler-" + UUID.randomUUID());
        UserEntity sender = persistUser("uid-hold-sender-" + UUID.randomUUID());
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(7));
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
        a = announcementRepository.save(a);
        bid = new BidEntity();
        bid.setAnnouncementId(a.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("5.00"));
        bid.setStatus(BidStatus.IN_TRANSIT);
        bid.setLinkedNegotiationThreadId(UUID.randomUUID());
        bid = bidRepository.save(bid);
    }

    private UserEntity persistUser(String uid) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(uid);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.VERIFIED);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.TRAVELER);
        roles.add(Role.SENDER);
        u.setRoles(roles);
        return userRepository.save(u);
    }

    private PaymentEntity escrow(boolean viaThread) {
        PaymentEntity p = new PaymentEntity();
        if (viaThread) {
            p.setNegotiationThreadId(bid.getLinkedNegotiationThreadId());
        } else {
            p.setBidId(bid.getId());
        }
        p.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        p.setAmount(new BigDecimal("50.00"));
        p.setCommissionAmount(new BigDecimal("6.00"));
        p.setStatus(PaymentStatus.ESCROW);
        return paymentRepository.saveAndFlush(p);
    }

    private long auditCount(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Long.class, traveler.getId(), action);
    }

    @Test
    void bannissementPuisLevee_geleEtDegele_avecAudit() {
        userService.banUser(traveler.getId(), "fraude", adminId);

        assertThat(holds.isHeld(traveler.getId())).isTrue();
        assertThat(holds.statusOf(traveler.getId()).reasons()).containsExactly(PayoutHoldReason.BANNED);
        assertThat(auditCount("PAYOUTS_HELD")).isEqualTo(1);

        userService.unsuspendUser(traveler.getId(), adminId);

        assertThat(holds.isHeld(traveler.getId())).isFalse();
        assertThat(auditCount("PAYOUTS_RELEASED_HOLD")).isEqualTo(1);
    }

    @Test
    void suspensionPuisLevee_neGeleRien() {
        userService.suspendUser(traveler.getId(), "enquete", adminId);
        assertThat(holds.isHeld(traveler.getId())).isFalse();
        userService.unsuspendUser(traveler.getId(), adminId);
        assertThat(auditCount("PAYOUTS_HELD")).isZero();
        assertThat(auditCount("PAYOUTS_RELEASED_HOLD")).isZero();
    }

    @Test
    void doubleMotif_leGelTientTantQuUnMotifReste() {
        userService.banUser(traveler.getId(), "fraude", adminId);
        events.publishEvent(new UserKycRevokedEvent(traveler.getId(), "document_fraud", adminId));
        assertThat(holds.statusOf(traveler.getId()).reasons())
                .containsExactly(PayoutHoldReason.BANNED, PayoutHoldReason.KYC_REVOKED);

        userService.unsuspendUser(traveler.getId(), adminId);
        assertThat(holds.isHeld(traveler.getId())).isTrue();
        assertThat(holds.statusOf(traveler.getId()).reasons()).containsExactly(PayoutHoldReason.KYC_REVOKED);

        events.publishEvent(new UserKycVerifiedEvent(traveler.getId()));
        assertThat(holds.isHeld(traveler.getId())).isFalse();

        // Un nouveau bannissement recree un gel : l'index unique partiel ne porte que sur les actifs.
        userService.banUser(traveler.getId(), "recidive", adminId);
        assertThat(holds.isHeld(traveler.getId())).isTrue();
    }

    @Test
    void paiementsRetenus_comptesEtFiltres_parVoyageur() {
        PaymentEntity classic = escrow(false);
        PaymentEntity thread = escrow(true);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        assertThat(paymentRepository.markPayoutHeld(classic.getId(), now)).isEqualTo(1);
        assertThat(paymentRepository.markPayoutHeld(classic.getId(), now.plusHours(1))).isZero();
        assertThat(paymentRepository.markPayoutHeld(thread.getId(), now)).isEqualTo(1);
        entityManager.clear();

        assertThat(paymentRepository.countHeldEscrowForTraveler(traveler.getId())).isEqualTo(2);
        assertThat(paymentRepository.countHeldEscrowForTraveler(UUID.randomUUID())).isZero();
        assertThat(adminPaymentInsights.search(com.yadony.api.admin.AdminPaymentFilter.of(null, null, null, null, null, true, null, null), PageRequest.of(0, 20))
                .getContent()).extracting(PaymentEntity::getId).contains(classic.getId(), thread.getId());
        List<Object[]> beneficiaries = paymentRepository.findBeneficiaries(List.of(classic.getId(), thread.getId()));
        assertThat(beneficiaries).hasSize(2)
                .allSatisfy(row -> assertThat(UUID.fromString(row[1].toString())).isEqualTo(traveler.getId()));
        assertThat(holds.summaryOf(traveler.getId()).heldPaymentsCount()).isEqualTo(2);
    }

    @Test
    void marqueDeRetenue_survitAuFlushDUneEntiteChargeeAvant() {
        PaymentEntity p = escrow(false);
        PaymentEntity loaded = paymentRepository.findById(p.getId()).orElseThrow();

        paymentRepository.markPayoutHeld(p.getId(), LocalDateTime.now(ZoneOffset.UTC));
        loaded.setRefundedAmount(BigDecimal.ONE);
        paymentRepository.saveAndFlush(loaded);

        Object heldAt = jdbc.queryForObject("SELECT payout_held_at FROM payments WHERE id = ?", Object.class, p.getId());
        assertThat(heldAt).isNotNull();
    }

    @Test
    void paiementLibere_sortDeLaFileDesRetenus() {
        PaymentEntity p = escrow(false);
        paymentRepository.markPayoutHeld(p.getId(), LocalDateTime.now(ZoneOffset.UTC));
        paymentRepository.markReleasedIfEscrow(p.getId(), LocalDateTime.now(ZoneOffset.UTC));
        entityManager.clear();

        assertThat(paymentRepository.countHeldEscrowForTraveler(traveler.getId())).isZero();
        assertThat(adminPaymentInsights.search(com.yadony.api.admin.AdminPaymentFilter.of(null, null, null, null, null, true, null, null), PageRequest.of(0, 20))
                .getContent()).extracting(PaymentEntity::getId).doesNotContain(p.getId());
    }
}
