package com.yadony.api.payments.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Filet de {@link WalletRefundIssueListener} : toutes les 5 minutes, reprend les demandes
 * automatiques PROCESSING (Stripe et pawaPay) dont un item est resté PENDING sans aucun
 * identifiant d'émission depuis plus de 2 minutes (crash du processus entre le commit et
 * l'émission, erreur de l'écouteur, dépôt pawaPay illisible). Le rejeu est sûr : côté Stripe la
 * clé d'idempotence {@code wallet-self-refund-<itemId>} empêche de rembourser deux fois le même
 * item ; côté pawaPay un item n'est repris que tant qu'aucune opération n'y est liée (le lien est
 * committé avant tout appel réseau).
 */
@Component
public class WalletRefundIssueRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(WalletRefundIssueRecoveryScheduler.class);

    /** Laisse à l'écouteur après commit le temps de finir avant toute reprise. */
    static final Duration GRACE = Duration.ofMinutes(2);

    /** Canaux émis automatiquement : Stripe et pawaPay passent tous deux par {@code issuePendingItems}. */
    static final List<WalletRefundChannel> AUTOMATIC_CHANNELS =
            List.of(WalletRefundChannel.AUTOMATIC_STRIPE, WalletRefundChannel.AUTOMATIC_PAWAPAY);

    private final WalletRefundRequestItemRepository refundRequestItemRepository;
    private final WalletSelfRefundService walletSelfRefundService;

    public WalletRefundIssueRecoveryScheduler(WalletRefundRequestItemRepository refundRequestItemRepository,
                                              WalletSelfRefundService walletSelfRefundService) {
        this.refundRequestItemRepository = refundRequestItemRepository;
        this.walletSelfRefundService = walletSelfRefundService;
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 60 * 1000)
    public void recoverUnissuedItems() {
        recoverItemsCreatedBefore(Instant.now().minus(GRACE));
    }

    /** Point d'entrée paramétré par la date limite, pour les tests. */
    void recoverItemsCreatedBefore(Instant cutoff) {
        List<UUID> requestIds = refundRequestItemRepository.findRequestIdsWithUnissuedItems(
                AUTOMATIC_CHANNELS, WalletRefundRequestStatus.PROCESSING,
                WalletRefundItemStatus.PENDING, cutoff);
        for (UUID requestId : requestIds) {
            log.warn("Reprise de l'emission pour la demande wallet {} : items PENDING "
                    + "non emis apres commit", requestId);
            try {
                walletSelfRefundService.issuePendingItems(requestId);
            } catch (RuntimeException e) {
                log.error("Reprise de l'emission en echec pour la demande wallet {}", requestId, e);
            }
        }
    }
}
