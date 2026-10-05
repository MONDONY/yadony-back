package com.yadony.api.payments.cash.job;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.CashCommissionProperties;
import com.yadony.api.payments.cash.CommissionStatus;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Commissions carte restées en 3-D Secure (REQUIRES_3DS) au-delà du délai : annule le
 * PaymentIntent puis remet le colis en état de retenter.
 *
 * <p>La remise à zéro incrémente {@code commission_retry_count}, donc la clé d'idempotence du
 * prochain prélèvement ({@code bid_accept_<bid>_v<n>}) : elle n'est sûre que si Stripe a bien
 * annulé le PaymentIntent. Un PaymentIntent déjà réussi (3DS aboutie, app fermée avant la
 * confirmation) garde son état — la confirmation de l'app finalisera l'acceptation — et un
 * échec Stripe ne remet rien à zéro : le passage suivant retente.
 */
@Component
public class OrphanedPaymentIntentCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OrphanedPaymentIntentCleanupJob.class);

    private final BidRepository bidRepo;
    private final CashCommissionProperties props;
    private final AdminAlertEscalator alertEscalator;

    public OrphanedPaymentIntentCleanupJob(BidRepository bidRepo, CashCommissionProperties props,
                                           AdminAlertEscalator alertEscalator) {
        this.bidRepo = bidRepo;
        this.props = props;
        this.alertEscalator = alertEscalator;
    }

    @Scheduled(cron = "${yadony.cash-commission.orphan-pi-cleanup-cron}", zone = "UTC")
    @Transactional
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC)
                .minusMinutes(props.orphanPiTimeoutMinutes());
        bidRepo.findByCommissionStatusAndUpdatedAtBefore(CommissionStatus.REQUIRES_3DS, cutoff)
                .forEach(this::cancelOrphan);
    }

    private void cancelOrphan(BidEntity bid) {
        String piId = bid.getCommissionPaymentIntentId();
        PaymentIntent pi;
        try {
            pi = PaymentIntent.retrieve(piId);
        } catch (StripeException e) {
            log.warn("PI {} illisible pour le bid {}, nouvel essai au prochain passage : {}",
                    piId, bid.getId(), e.getMessage());
            return;
        }

        String status = pi.getStatus();
        if ("succeeded".equals(status)) {
            log.warn("Commission PI {} du bid {} déjà encaissée mais jamais confirmée par l'app", piId, bid.getId());
            alertEscalator.raiseOnce("COMMISSION_3DS_UNCONFIRMED_" + bid.getId(),
                    "Commission encaissée (3DS aboutie) mais acceptation jamais confirmée par l'app pour le bid "
                            + bid.getId() + " : finaliser l'acceptation ou rembourser la commission",
                    Map.of("bidId", bid.getId().toString(), "paymentIntentId", piId));
            return;
        }
        if ("processing".equals(status)) {
            // Issue encore inconnue : on attend le passage suivant.
            return;
        }
        if (!"canceled".equals(status)) {
            try {
                pi.cancel();
            } catch (StripeException e) {
                log.warn("Annulation du PI {} refusée pour le bid {}, nouvel essai au prochain passage : {}",
                        piId, bid.getId(), e.getMessage());
                return;
            }
        }
        bid.setCommissionStatus(null);
        bid.setCommissionPaymentIntentId(null);
        bid.setCommissionRetryCount(bid.getCommissionRetryCount() + 1);
        bidRepo.save(bid);
    }
}
