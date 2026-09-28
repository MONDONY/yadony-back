package com.yadony.api.cancellation;

import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class CancellationRepositoryAdminNoShowTest {

    @Autowired CancellationRepository cancellationRepository;
    @Autowired BidRepository bidRepository;

    private CancellationEntity persist(CancellationScope scope, String reason, CancellationStatus status) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(UUID.randomUUID());
        bid.setSenderId(UUID.randomUUID());
        bid.setWeightKg(new java.math.BigDecimal("5.0"));
        bid.setStatus(BidStatus.ACCEPTED);
        bid = bidRepository.save(bid);
        CancellationEntity c = new CancellationEntity();
        c.setBidId(bid.getId());
        c.setCancelledBy(bid.getSenderId());
        c.setScope(scope);
        c.setReason(reason);
        c.setNoShowStatus(status);
        c.setContestationDeadline(OffsetDateTime.now().minusHours(1));
        return cancellationRepository.save(c);
    }

    @Test
    void findAdminNoShows_neListeQueLesNoShowsFiltresParPorteeEtStatut() {
        CancellationEntity handover = persist(CancellationScope.HANDOVER, "SENDER_NO_SHOW",
                CancellationStatus.PENDING_CONFIRMATION);
        CancellationEntity delivery = persist(CancellationScope.DELIVERY, "RECIPIENT_NO_SHOW",
                CancellationStatus.PENDING_CONFIRMATION);
        persist(CancellationScope.DELIVERY, "TRAVELER_DELIVERY_NO_SHOW", CancellationStatus.CONTESTED);
        // Annulations qui ne sont pas des no-shows : jamais listées.
        persist(CancellationScope.HANDOVER, "TRIP_CANCELLED", CancellationStatus.PENDING_CONFIRMATION);
        persist(CancellationScope.HANDOVER, "SENDER_CANCEL_AFTER_HANDOVER", CancellationStatus.CONFIRMED);

        var pageable = PageRequest.of(0, 20, Sort.by("createdAt").descending());
        var pending = cancellationRepository.findAdminNoShows(NoShowReasons.ALL,
                List.of(CancellationScope.values()), List.of(CancellationStatus.PENDING_CONFIRMATION), pageable);
        assertThat(pending.getContent()).extracting(CancellationEntity::getId)
                .containsExactlyInAnyOrder(handover.getId(), delivery.getId());

        var deliveryOnly = cancellationRepository.findAdminNoShows(NoShowReasons.ALL,
                List.of(CancellationScope.DELIVERY), List.of(CancellationStatus.values()), pageable);
        assertThat(deliveryOnly.getTotalElements()).isEqualTo(2);
        assertThat(deliveryOnly.getContent()).allMatch(c -> c.getScope() == CancellationScope.DELIVERY);
    }

    @Test
    void decisionAdmin_persisteeEtIgnoreeParLesSchedulersDEcheance() {
        CancellationEntity handover = persist(CancellationScope.HANDOVER, "SENDER_NO_SHOW",
                CancellationStatus.PENDING_CONFIRMATION);
        CancellationEntity delivery = persist(CancellationScope.DELIVERY, "RECIPIENT_NO_SHOW",
                CancellationStatus.PENDING_CONFIRMATION);
        for (CancellationEntity c : List.of(handover, delivery)) {
            c.setNoShowStatus(CancellationStatus.RESOLVED);
            c.setAdminDecision(NoShowAdminDecision.REJECTED);
            c.setDecidedByAdminId(UUID.randomUUID());
            c.setDecidedAt(OffsetDateTime.now());
            c.setDecisionReason("Le colis a bien été remis selon les photos");
            cancellationRepository.saveAndFlush(c);
        }

        // Délai expiré, mais la ligne est classée : NoShowContestationTimeoutJob et
        // DeliveryNoShowUncontestedScheduler ne la sélectionnent plus.
        assertThat(cancellationRepository.findExpiredPending(OffsetDateTime.now())).isEmpty();
        assertThat(cancellationRepository.findExpiredPendingByScope(CancellationScope.DELIVERY,
                OffsetDateTime.now())).isEmpty();

        CancellationEntity reloaded = cancellationRepository.findById(handover.getId()).orElseThrow();
        assertThat(reloaded.getAdminDecision()).isEqualTo(NoShowAdminDecision.REJECTED);
        assertThat(reloaded.getDecisionReason()).isEqualTo("Le colis a bien été remis selon les photos");
        assertThat(reloaded.getDecidedAt()).isNotNull();
        assertThat(reloaded.getDecidedByAdminId()).isNotNull();
    }
}
