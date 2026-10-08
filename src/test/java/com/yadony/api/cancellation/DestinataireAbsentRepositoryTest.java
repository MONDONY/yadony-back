package com.yadony.api.cancellation;

import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Requêtes JPQL de la procédure « destinataire absent » et du partage (FLUTTER-E2), sur H2. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DestinataireAbsentRepositoryTest {

    @Autowired CancellationRepository cancellationRepository;
    @Autowired BidRepository bidRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired EntityManager entityManager;

    private BidEntity persistBid() {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(UUID.randomUUID());
        bid.setSenderId(UUID.randomUUID());
        bid.setWeightKg(new BigDecimal("5.0"));
        bid.setStatus(BidStatus.ARRIVED);
        bid.setArrivedAt(LocalDateTime.now(ZoneOffset.UTC));
        return bidRepository.save(bid);
    }

    private CancellationEntity hold(UUID bidId, CancellationStatus status, OffsetDateTime holdUntil) {
        CancellationEntity c = new CancellationEntity();
        c.setBidId(bidId);
        c.setCancelledBy(UUID.randomUUID());
        c.setReason("RECIPIENT_NO_SHOW");
        c.setScope(CancellationScope.DELIVERY);
        c.setNoShowStatus(status);
        c.setHoldUntil(holdUntil);
        c.setContactProof("CALL");
        return cancellationRepository.saveAndFlush(c);
    }

    @Test
    void gardesEchues_seulementConfirmeesEtNonReclamees_puisClaimUnique() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CancellationEntity due = hold(persistBid().getId(), CancellationStatus.CONFIRMED, now.minusHours(1));
        hold(persistBid().getId(), CancellationStatus.CONTESTED, now.minusHours(1));
        hold(persistBid().getId(), CancellationStatus.CONFIRMED, now.plusDays(1));
        hold(persistBid().getId(), CancellationStatus.CONFIRMED, null);

        assertThat(cancellationRepository.findDueUnclaimedHolds(now)).extracting(CancellationEntity::getId)
                .containsExactly(due.getId());

        assertThat(cancellationRepository.markUnclaimed(due.getId(), now)).isEqualTo(1);
        assertThat(cancellationRepository.markUnclaimed(due.getId(), now)).isZero();
        entityManager.clear();
        CancellationEntity reloaded = cancellationRepository.findById(due.getId()).orElseThrow();
        assertThat(reloaded.getUnclaimedAt()).isNotNull();
        assertThat(cancellationRepository.findDueUnclaimedHolds(now)).isEmpty();

        // Un flush ultérieur de l'entité ne peut pas effacer la marque (colonne non modifiable).
        reloaded.setRetryAppointmentNote("x");
        cancellationRepository.saveAndFlush(reloaded);
        entityManager.clear();
        assertThat(cancellationRepository.findById(due.getId()).orElseThrow().getUnclaimedAt()).isNotNull();
    }

    @Test
    void claimForSplit_sortDuSequestreUneSeuleFois() {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(UUID.randomUUID());
        p.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        p.setAmount(new BigDecimal("105.00"));
        p.setCommissionAmount(new BigDecimal("5.00"));
        p.setStatus(PaymentStatus.ESCROW);
        p = paymentRepository.saveAndFlush(p);

        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC);
        assertThat(paymentRepository.claimForSplit(p.getId(), PaymentStatus.RELEASED, at, new BigDecimal("40.00")))
                .isEqualTo(1);
        assertThat(paymentRepository.claimForSplit(p.getId(), PaymentStatus.RELEASED, at, new BigDecimal("40.00")))
                .isZero();
        entityManager.clear();
        PaymentEntity reloaded = paymentRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(reloaded.getRefundedAmount()).isEqualByComparingTo("40.00");
        assertThat(reloaded.getEscrowReleasedAt()).isNotNull();
    }
}
