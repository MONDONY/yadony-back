package com.yadony.api.referral;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.referral.events.ReferralRewardAvailableEvent;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Listens for {@link DeliveryConfirmedEvent} after the tracking transaction commits,
 * then makes the referrer's reward available if this is the referee's first completed
 * delivery.
 *
 * <p>Uses {@code @TransactionalEventListener(phase = AFTER_COMMIT)} + a new transaction
 * so we read data that is guaranteed to be committed (CLAUDE.md rule #18).
 *
 * <p>Depuis V321, rien n'est octroyé d'office : l'invitation passe en
 * {@code FIRST_DELIVERY} / {@link ReferralRewardStatus#AVAILABLE} et le parrain choisit
 * ensuite sa récompense ({@link ReferralService#chooseReward}). Les bons -50 % déjà
 * octroyés avant restent consommables. Un {@link ReferralRewardAvailableEvent} prévient
 * le parrain (paquet {@code notifications}).
 */
@Component
public class DeliveryConfirmedReferralListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryConfirmedReferralListener.class);

    private final ReferralInvitationRepository referralInvitationRepository;
    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public DeliveryConfirmedReferralListener(ReferralInvitationRepository referralInvitationRepository,
                                              BidRepository bidRepository,
                                              UserRepository userRepository,
                                              AuditService auditService,
                                              ApplicationEventPublisher eventPublisher) {
        this.referralInvitationRepository = referralInvitationRepository;
        this.bidRepository = bidRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        try {
            makeRewardAvailable(event);
        } catch (Exception ex) {
            // REQUIRES_NEW rolls back silently; log explicitly so ops can investigate
            log.error("Referral reward failed for bid={} sender={}: {}",
                    event.getBidId(), event.getSenderId(), ex.getMessage(), ex);
            throw ex;
        }
    }

    private void makeRewardAvailable(DeliveryConfirmedEvent event) {
        UUID senderId = event.getSenderId();

        Optional<ReferralInvitationEntity> invOpt =
                referralInvitationRepository.findByRefereeUserIdAndStatus(senderId, "SIGNED_UP");

        if (invOpt.isEmpty()) {
            log.debug("No SIGNED_UP referral invitation for sender {}, skipping reward", senderId);
            return;
        }

        long completedCount = bidRepository.countByStatusAndSenderId(BidStatus.COMPLETED, senderId);

        if (completedCount == 0) {
            // Should not happen — bid was just committed as COMPLETED. Log a warning so
            // this edge case is visible in monitoring rather than silently skipped.
            log.warn("Referral reward: completed-bid count is 0 for sender {} bid {} — " +
                     "possible Hibernate timing issue; skipping to avoid duplicate reward on retry",
                    senderId, event.getBidId());
            return;
        }

        if (completedCount > 1) {
            log.debug("Sender {} has {} completed bids — not the first, skipping referral reward",
                    senderId, completedCount);
            return;
        }

        // completedCount == 1 → this IS the first completed delivery
        // Mise à jour conditionnelle : deux confirmations concurrentes (ou un événement
        // rejoué) ne rendent la récompense disponible, et ne notifient, qu'une fois.
        ReferralInvitationEntity inv = invOpt.get();
        if (referralInvitationRepository.markRewardAvailableIfSignedUp(
                inv.getId(), LocalDateTime.now(ZoneOffset.UTC)) == 0) {
            log.info("Referral reward already made available for invitation {} (idempotent skip)", inv.getId());
            return;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("referrerId", inv.getReferrerUserId().toString());
        details.put("refereeId", senderId.toString());
        details.put("bidId", event.getBidId().toString());
        auditService.log("REFERRAL_INVITATION", inv.getId(), "REFERRAL_REWARD_AVAILABLE",
                inv.getReferrerUserId(), details);

        String refereeName = userRepository.findById(senderId).map(UserEntity::publicDisplayName).orElse(null);
        eventPublisher.publishEvent(
                new ReferralRewardAvailableEvent(inv.getId(), inv.getReferrerUserId(), refereeName));

        log.info("Referral reward available: referrer={} referee={} invitation={}",
                inv.getReferrerUserId(), senderId, inv.getId());
    }
}
