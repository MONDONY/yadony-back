package com.yadony.api.referral;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.yadony.api.voucher.CommissionVoucherEntity;
import com.yadony.api.voucher.CommissionVoucherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Listens for {@link DeliveryConfirmedEvent} after the tracking transaction commits,
 * then rewards the referrer if this is the referee's first completed delivery.
 *
 * <p>Uses {@code @TransactionalEventListener(phase = AFTER_COMMIT)} + a new transaction
 * so we read data that is guaranteed to be committed (CLAUDE.md rule #18).
 *
 * <p>Lot 3 (2026-08-19/20) : la récompense n'est plus un crédit portefeuille — c'est un
 * bon de réduction de commission (50 % par défaut, 6 mois de validité, voir
 * {@link CommissionVoucherService}). Injection directe plutôt qu'un event : ce n'est pas
 * un domaine métier propre (comme {@code payments/wallet} l'était), c'est une règle de
 * tarification partagée, exactement comme {@code common/CommissionRateResolver} injecte
 * directement {@code promo/PromoService}.
 */
@Component
public class DeliveryConfirmedReferralListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryConfirmedReferralListener.class);

    private final ReferralInvitationRepository referralInvitationRepository;
    private final BidRepository bidRepository;
    private final AuditService auditService;
    private final CommissionVoucherService voucherService;

    public DeliveryConfirmedReferralListener(ReferralInvitationRepository referralInvitationRepository,
                                              BidRepository bidRepository,
                                              AuditService auditService,
                                              CommissionVoucherService voucherService) {
        this.referralInvitationRepository = referralInvitationRepository;
        this.bidRepository = bidRepository;
        this.auditService = auditService;
        this.voucherService = voucherService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        try {
            doReward(event);
        } catch (Exception ex) {
            // REQUIRES_NEW rolls back silently; log explicitly so ops can investigate
            log.error("Referral reward failed for bid={} sender={}: {}",
                    event.getBidId(), event.getSenderId(), ex.getMessage(), ex);
            throw ex;
        }
    }

    private void doReward(DeliveryConfirmedEvent event) {
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
        ReferralInvitationEntity inv = invOpt.get();
        inv.setStatus("REWARDED");
        inv.setRewardedAt(LocalDateTime.now(ZoneOffset.UTC));
        referralInvitationRepository.save(inv);

        CommissionVoucherEntity voucher = voucherService.grant(inv.getReferrerUserId(), inv.getId());

        auditService.log(
                "REFERRAL_INVITATION",
                inv.getId(),
                "REFERRAL_REWARDED",
                inv.getReferrerUserId(),
                Map.of(
                        "referrerId", inv.getReferrerUserId().toString(),
                        "refereeId", senderId.toString(),
                        "voucherId", voucher.getId().toString(),
                        "factor", voucher.getFactor().toPlainString(),
                        "bidId", event.getBidId().toString()
                )
        );

        log.info("Referral voucher granted: referrer={} referee={} voucher={} factor={}",
                inv.getReferrerUserId(), senderId, voucher.getId(), voucher.getFactor());
    }
}
