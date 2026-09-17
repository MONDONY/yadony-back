package com.yadony.api.payments.wallet;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.payments.pawapay.dto.PawapayInitiationResult;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Émetteur du rail pawaPay des remboursements de wallet ({@code AUTOMATIC_PAWAPAY}). Chaque item
 * rembourse le NET ({@code amount - feeAmount}) dans la devise du dépôt d'origine : par un
 * remboursement partiel du dépôt quand l'opérateur le propose ({@code operationTypes.REFUND} de
 * la configuration active), sinon par un versement (payout) vers le numéro qui a payé. Un
 * remboursement en échec retombe sur ce versement ({@link #fallbackToPayout}) ; un versement en
 * échec passe l'item FAILED et alerte un administrateur (le ticket enfant s'ouvre à la résolution
 * de la demande). Le wallet n'est débité, du brut, qu'à cette résolution
 * ({@link WalletSelfRefundService#resolveIfComplete}).
 *
 * <p><b>Anti double remboursement.</b> L'émission se fait en deux temps. Dans la transaction
 * appelante, l'opération est réservée ({@code PawapaySubmissionService#createWalletRefund} /
 * {@code #createWalletPayout}, committée à part), son identifiant posé sur l'item passé
 * PROCESSING, puis {@link WalletPawapayRefundInitiationEvent} est publié. L'appel réseau
 * ({@link #initiate}) ne part qu'au commit de cette transaction. Un item PROCESSING lié n'est
 * jamais repris par {@link WalletRefundIssueRecoveryScheduler} : une initiation sans réponse est
 * tranchée par le poller pawaPay (FAILED, puis {@link WalletRefundOutcomeListener}).
 *
 * <p>Les lectures d'opération passent par {@link PawapayOperationRepository#findById}, jamais par
 * {@code PawapayOperationService#get} : ce dernier lève dans une méthode {@code @Transactional}
 * participante, ce qui marquerait rollback-only la transaction de {@code issuePendingItems} même
 * si l'exception est rattrapée ici.
 */
@Component
public class WalletPawapayRefundIssuer implements WalletRefundRailIssuer {

    /** Même code que les échecs Stripe de {@link WalletSelfRefundService}. */
    static final String ALERT_CODE = "wallet-self-refund-failed";

    /**
     * Âge maximal d'une opération CREATED au moment de l'envoyer. Au-delà de
     * {@code PawapayReconciliationPoller.CREATED_TIMEOUT} (2 min), le poller classe une opération
     * CREATED inconnue de pawaPay en SUBMIT_REJECTED et l'écouteur lance le repli par versement :
     * un POST parti après ce seuil pourrait être accepté EN PLUS du versement (double mouvement).
     * Les initiations après commit passent en série, chacune pouvant durer 10 s de connexion
     * + 30 s de lecture ({@code PawapayConfig}) : envoyer au plus tard à 60 s garantit une
     * réponse avant 100 s, sous les 120 s du poller, avec 20 s de marge. Plus vieille, l'opération
     * reste CREATED : le poller la rejette et le repli part sans risque.
     */
    static final Duration MAX_SEND_AGE = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(WalletPawapayRefundIssuer.class);

    private final PawapayOperationRepository operationRepository;
    private final PawapaySubmissionService submission;
    private final PawapayClient client;
    private final WalletRefundRequestItemRepository itemRepository;
    private final AuditService auditService;
    private final AdminAlertService adminAlertService;
    private final ApplicationEventPublisher eventPublisher;

    public WalletPawapayRefundIssuer(PawapayOperationRepository operationRepository,
                                     PawapaySubmissionService submission, PawapayClient client,
                                     WalletRefundRequestItemRepository itemRepository, AuditService auditService,
                                     AdminAlertService adminAlertService, ApplicationEventPublisher eventPublisher) {
        this.operationRepository = operationRepository;
        this.submission = submission;
        this.client = client;
        this.itemRepository = itemRepository;
        this.auditService = auditService;
        this.adminAlertService = adminAlertService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Réserve et lie une opération par item non encore émis. Une erreur AVANT la réservation
     * (dépôt introuvable, configuration pawaPay illisible, réservation refusée) laisse l'item
     * PENDING sans identifiant : la reprise planifiée réessaiera.
     */
    @Override
    public void issue(WalletRefundRequestEntity request, List<WalletRefundRequestItemEntity> items) {
        for (WalletRefundRequestItemEntity item : items) {
            if (item.getPawapayRefundId() != null || item.getPawapayPayoutId() != null) {
                continue;
            }
            Optional<PawapayOperationEntity> deposit = findDeposit(item);
            if (deposit.isEmpty()) {
                continue;
            }
            Optional<Boolean> refundable = supportsRefund(deposit.get());
            if (refundable.isEmpty()) {
                log.warn("Remboursement wallet pawaPay : configuration active illisible pour l'item {}, "
                        + "reprise planifiee", item.getId());
                continue;
            }
            PawapayOperationKind kind = refundable.get() ? PawapayOperationKind.REFUND : PawapayOperationKind.PAYOUT;
            emit(item, request.getUserId(), deposit.get(), kind);
        }
    }

    /** Rejet synchrone explicite d'une initiation : à traiter comme une issue finale en échec. */
    record Rejection(PawapayOperationKind kind, UUID operationId, String failureCode) {}

    /**
     * Second temps de l'émission, après le commit du lien : envoie l'opération à pawaPay, relue
     * juste avant le POST (toujours CREATED, et assez récente, voir {@link #MAX_SEND_AGE}). Sans
     * réponse, l'item reste lié et PROCESSING (log WARN, rien n'est propagé) : le poller pawaPay
     * tranchera. Un rejet synchrone explicite ({@code SUBMIT_REJECTED}, que
     * {@code markSubmitted} ne publie pas en événement) est rendu à l'appelant, qui le traite
     * comme un échec final (verrou de la demande puis {@link #applyPawapayOutcome}).
     *
     * @return le rejet à traiter ; vide sinon
     */
    Optional<Rejection> initiate(UUID itemId, UUID operationId) {
        PawapayOperationEntity op = operationRepository.findById(operationId).orElse(null);
        if (op == null) {
            log.warn("Remboursement wallet pawaPay : operation {} de l'item {} introuvable", operationId, itemId);
            return Optional.empty();
        }
        if (op.getStatus() != PawapayOperationStatus.CREATED) {
            log.info("Remboursement wallet pawaPay : operation {} deja {}, initiation ignoree",
                    operationId, op.getStatus());
            return Optional.empty();
        }
        LocalDateTime sendDeadline = LocalDateTime.now(ZoneOffset.UTC).minus(MAX_SEND_AGE);
        if (op.getCreatedAt() == null || op.getCreatedAt().isBefore(sendDeadline)) {
            log.warn("Remboursement wallet pawaPay : {} {} de l'item {} creee a {}, trop ancienne pour etre "
                    + "envoyee sans risque, laissee CREATED au poller", op.getKind(), operationId, itemId,
                    op.getCreatedAt());
            return Optional.empty();
        }
        PawapayInitiationResult result;
        try {
            result = submission.initiate(op, "wallet-refund-" + itemId);
        } catch (YadonyBusinessException e) {
            log.warn("Remboursement wallet pawaPay : {} {} de l'item {} sans reponse, item laisse PROCESSING "
                    + "pour le poller", op.getKind(), operationId, itemId);
            return Optional.empty();
        }
        if (result.outcome() != PawapayInitiationResult.Outcome.REJECTED) {
            return Optional.empty();
        }
        log.warn("Remboursement wallet pawaPay : {} {} de l'item {} rejete a l'initiation ({})",
                op.getKind(), operationId, itemId, result.failureCode());
        return Optional.of(new Rejection(op.getKind(), operationId, result.failureCode()));
    }

    /** Item lié à l'opération {@code operationId}, verrouillé. */
    Optional<WalletRefundRequestItemEntity> findItem(PawapayOperationKind kind, UUID operationId) {
        return switch (kind) {
            case REFUND -> itemRepository.findByPawapayRefundId(operationId);
            case PAYOUT -> itemRepository.findByPawapayPayoutId(operationId);
            case DEPOSIT -> Optional.empty();
        };
    }

    /**
     * Transition partagée d'une issue pawaPay finale vers l'item (écouteur, rejet synchrone,
     * réconciliation). Idempotente : un item qui n'est plus PROCESSING n'avance plus, et l'échec
     * d'un remboursement dont l'item porte déjà un versement ne relance rien. L'appelant tient le
     * verrou de la demande {@code request} (pris AVANT celui de l'item) et la résout ensuite.
     */
    void applyPawapayOutcome(WalletRefundRequestEntity request, WalletRefundRequestItemEntity item,
                             PawapayOperationKind kind, boolean completed, String failureCode) {
        if (item.getStatus() != WalletRefundItemStatus.PROCESSING) {
            log.info("Remboursement wallet pawaPay : item {} deja {}, issue {} ignoree",
                    item.getId(), item.getStatus(), kind);
            return;
        }
        if (completed) {
            item.setStatus(WalletRefundItemStatus.REFUNDED);
            itemRepository.save(item);
            return;
        }
        if (kind == PawapayOperationKind.REFUND) {
            if (item.getPawapayPayoutId() != null) {
                log.info("Remboursement wallet pawaPay : item {} deja replie sur le versement {}",
                        item.getId(), item.getPawapayPayoutId());
                return;
            }
            Optional<PawapayOperationEntity> deposit = findDeposit(item);
            if (deposit.isEmpty()) {
                fail(item, "pawapay-deposit-missing", "Depot d'origine introuvable pour le repli par versement");
                return;
            }
            fallbackToPayout(request, item, deposit.get(), failureCode);
            return;
        }
        fail(item, failureCode != null ? failureCode : "pawapay-payout-failed",
                "Versement pawaPay echoue pour un remboursement wallet self-service");
    }

    /**
     * Remboursement pawaPay en échec : même émission que {@link #issue}, en versement vers le
     * numéro du dépôt. Une réservation impossible ici n'a plus de reprise planifiée (l'item est
     * déjà PROCESSING) : l'item passe FAILED et un administrateur est alerté.
     */
    void fallbackToPayout(WalletRefundRequestEntity request, WalletRefundRequestItemEntity item,
                          PawapayOperationEntity deposit, String refundFailureCode) {
        UUID refundOperationId = item.getPawapayRefundId();
        Optional<UUID> payoutId = emit(item, request.getUserId(), deposit, PawapayOperationKind.PAYOUT);
        if (payoutId.isEmpty()) {
            fail(item, "pawapay-fallback-not-created", "Repli par versement pawaPay impossible a reserver");
            return;
        }
        auditService.log("wallet_refund_request", item.getRefundRequestId(), "REFUND_FALLBACK_PAYOUT",
                request.getUserId(), Map.of("itemId", String.valueOf(item.getId()),
                        "refundOperationId", String.valueOf(refundOperationId),
                        "payoutOperationId", payoutId.get().toString(),
                        "refundFailureCode", String.valueOf(refundFailureCode)));
    }

    private Optional<UUID> emit(WalletRefundRequestItemEntity item, UUID userId, PawapayOperationEntity deposit,
                                PawapayOperationKind kind) {
        BigDecimal net = item.getAmount().subtract(item.getFeeAmount());
        PawapayOperationEntity op;
        try {
            op = kind == PawapayOperationKind.REFUND
                    ? submission.createWalletRefund(userId, deposit, net)
                    : submission.createWalletPayout(userId, deposit.getMsisdn(), deposit.getProvider(),
                            deposit.getCountry(), net, deposit.getCurrency());
        } catch (RuntimeException e) {
            log.warn("Remboursement wallet pawaPay : reservation {} impossible pour l'item {} : {}",
                    kind, item.getId(), e.toString());
            return Optional.empty();
        }
        if (kind == PawapayOperationKind.REFUND) {
            item.setPawapayRefundId(op.getId());
        } else {
            item.setPawapayPayoutId(op.getId());
        }
        item.setStatus(WalletRefundItemStatus.PROCESSING);
        itemRepository.save(item);
        eventPublisher.publishEvent(new WalletPawapayRefundInitiationEvent(item.getId(), op.getId()));
        return Optional.of(op.getId());
    }

    private Optional<PawapayOperationEntity> findDeposit(WalletRefundRequestItemEntity item) {
        UUID depositId;
        try {
            depositId = WalletRefundRail.depositId(item.getPaymentIntentId());
        } catch (IllegalArgumentException e) {
            log.warn("Remboursement wallet pawaPay : reference de paiement illisible pour l'item {}", item.getId());
            return Optional.empty();
        }
        Optional<PawapayOperationEntity> deposit = operationRepository.findById(depositId);
        if (deposit.isEmpty()) {
            log.warn("Remboursement wallet pawaPay : depot {} introuvable pour l'item {}", depositId, item.getId());
        }
        return deposit;
    }

    /** Vide si la configuration active pawaPay n'a pas pu être lue. Opérateur absent : pas de refund. */
    private Optional<Boolean> supportsRefund(PawapayOperationEntity deposit) {
        try {
            PawapayProviderConfig config = client.activeConfiguration().get(deposit.getProvider());
            return Optional.of(config != null && config.supportsRefund());
        } catch (RuntimeException e) {
            log.warn("Remboursement wallet pawaPay : configuration active indisponible ({})", e.toString());
            return Optional.empty();
        }
    }

    private void fail(WalletRefundRequestItemEntity item, String reason, String detail) {
        item.setStatus(WalletRefundItemStatus.FAILED);
        item.setFailureReason(reason.length() <= 60 ? reason : reason.substring(0, 60));
        itemRepository.save(item);
        adminAlertService.raise(ALERT_CODE, detail,
                Map.of("itemId", String.valueOf(item.getId()),
                        "refundRequestId", String.valueOf(item.getRefundRequestId()),
                        "pawapayRefundId", String.valueOf(item.getPawapayRefundId()),
                        "pawapayPayoutId", String.valueOf(item.getPawapayPayoutId()),
                        "reason", reason));
    }
}
