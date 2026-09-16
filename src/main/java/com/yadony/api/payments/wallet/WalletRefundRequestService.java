package com.yadony.api.payments.wallet;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Ouvre le ticket de remboursement wallet — automatiquement à chaque suppression de compte
 * en solde positif (cf. {@code UserService#settleWalletsForDeletion}, jamais bloquant :
 * Apple 5.1.1(v)), ou explicitement via {@code POST /auth/me/wallet-refund-request} pour qui
 * veut être remboursé sans supprimer son compte. Aucun flow de remboursement automatique
 * n'existe côté Stripe pour le wallet : un admin rembourse manuellement hors-app puis résout
 * le ticket, ce qui débite le wallet à zéro.
 */
@Service
public class WalletRefundRequestService {

    private static final Logger log = LoggerFactory.getLogger(WalletRefundRequestService.class);

    private final WalletService walletService;
    private final WalletRefundRequestRepository refundRequestRepository;
    private final AuditService auditService;
    private final AdminAlertService adminAlertService;
    private final WalletRefundRequestItemRepository refundRequestItemRepository;

    public WalletRefundRequestService(WalletService walletService,
                                       WalletRefundRequestRepository refundRequestRepository,
                                       AuditService auditService,
                                       AdminAlertService adminAlertService,
                                       WalletRefundRequestItemRepository refundRequestItemRepository) {
        this.walletService = walletService;
        this.refundRequestRepository = refundRequestRepository;
        this.auditService = auditService;
        this.adminAlertService = adminAlertService;
        this.refundRequestItemRepository = refundRequestItemRepository;
    }

    /** Ticket manuel pour une seule devise (repli quand le rail automatique ne s'applique pas). */
    @Transactional
    public WalletRefundRequestEntity request(UUID userId, String currency) {
        String code = currency.trim().toUpperCase(Locale.ROOT);
        WalletAccountEntity wallet = walletService.getAllBalances(userId).stream()
                .filter(w -> code.equalsIgnoreCase(w.getCurrency()))
                .filter(w -> w.getBalance().compareTo(BigDecimal.ZERO) > 0)
                .findFirst()
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "wallet-balance-empty", "Unprocessable", "Aucun solde à rembourser"));
        return requestForCurrency(userId, wallet);
    }

    /**
     * Après une demande automatique dont au moins un item a échoué côté Stripe : ticket manuel
     * pour la somme des items FAILED, rattaché au parent. Idempotent : un seul enfant par parent.
     * Renvoie null si l'enfant existe déjà.
     *
     * <p>Chaque item en échec est repris par un item PENDING de l'enfant (même recharge, même
     * PaymentIntent, même montant, sans {@code stripeRefundId}) : la résolution admin les passe
     * REFUNDED, et l'allocateur rapproche alors l'{@code ADMIN_REFUND_OUT} de la bonne recharge
     * au lieu de retomber en LIFO (cf. {@link WalletRefundAllocator}).
     */
    @Transactional
    public WalletRefundRequestEntity openChildForFailedItems(WalletRefundRequestEntity parent,
                                                             List<WalletRefundRequestItemEntity> failedItems) {
        if (failedItems == null || failedItems.isEmpty()) {
            return null;
        }
        BigDecimal failedAmount = failedItems.stream()
                .map(WalletRefundRequestItemEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (failedAmount.signum() <= 0) {
            return null;
        }
        if (refundRequestRepository.existsByParentRequestId(parent.getId())) {
            return null;
        }
        WalletRefundRequestEntity child = new WalletRefundRequestEntity();
        child.setUserId(parent.getUserId());
        child.setCurrency(parent.getCurrency());
        child.setAmount(failedAmount);
        child.setChannel(WalletRefundChannel.MANUAL_ADMIN);
        child.setStatus(WalletRefundRequestStatus.PENDING);
        child.setRequestedAt(LocalDateTime.now(ZoneOffset.UTC));
        child.setParentRequestId(parent.getId());
        WalletRefundRequestEntity saved = refundRequestRepository.save(child);

        List<WalletRefundRequestItemEntity> childItems = new ArrayList<>();
        for (WalletRefundRequestItemEntity failed : failedItems) {
            WalletRefundRequestItemEntity item = new WalletRefundRequestItemEntity();
            item.setRefundRequestId(saved.getId());
            item.setWalletTransactionId(failed.getWalletTransactionId());
            item.setPaymentIntentId(failed.getPaymentIntentId());
            item.setAmount(failed.getAmount());
            item.setStatus(WalletRefundItemStatus.PENDING);
            childItems.add(item);
        }
        refundRequestItemRepository.saveAll(childItems);

        auditService.log("wallet_refund_request", saved.getId(), "MANUAL_CHILD_OPENED", parent.getUserId(),
                Map.of("currency", parent.getCurrency(), "amount", failedAmount.toPlainString(),
                        "parentRequestId", String.valueOf(parent.getId()),
                        "itemCount", String.valueOf(childItems.size())));
        adminAlertService.raise("wallet-refund-requested",
                "Remboursement Stripe automatique echoue, ticket manuel ouvert",
                Map.of("requestId", String.valueOf(saved.getId()), "parentRequestId", String.valueOf(parent.getId()),
                        "userId", String.valueOf(parent.getUserId()), "currency", parent.getCurrency(),
                        "amount", failedAmount.toPlainString()));
        return saved;
    }

    /**
     * Idempotence par (utilisateur, devise, statut, canal) : seul un ticket MANUAL encore
     * ouvert vaut « rien à refaire ». Une demande AUTOMATIC_STRIPE en vol n'est pas un ticket
     * admin — la renvoyer telle quelle laissait croire à l'appelant qu'un humain allait
     * reprendre le solde, alors que le rail automatique le traite déjà et qu'un échec d'item
     * ouvrira son propre ticket enfant ({@link #openChildForFailedItems}). On ne peut pas non
     * plus ouvrir un second ticket par-dessus : l'index unique partiel
     * {@code uq_wallet_refund_requests_pending} (V229) n'autorise qu'une demande
     * PENDING/PROCESSING par (user_id, currency). La demande en cours est donc renvoyée sans
     * rien créer, et tracée pour qu'un règlement manuel resté sans ticket soit visible.
     */
    private WalletRefundRequestEntity requestForCurrency(UUID userId, WalletAccountEntity wallet) {
        WalletRefundRequestEntity existing = refundRequestRepository
                .findByUserIdAndCurrencyAndStatusIn(userId, wallet.getCurrency(),
                        List.of(WalletRefundRequestStatus.PENDING, WalletRefundRequestStatus.PROCESSING))
                .orElse(null);
        if (existing != null) {
            if (existing.getChannel() != WalletRefundChannel.MANUAL_ADMIN) {
                log.warn("Ticket manuel non ouvert pour user {} devise {} : la demande "
                                + "automatique {} est encore en cours sur cette devise",
                        userId, wallet.getCurrency(), existing.getId());
            }
            return existing;
        }

        WalletRefundRequestEntity request = new WalletRefundRequestEntity();
        request.setUserId(userId);
        request.setCurrency(wallet.getCurrency());
        request.setAmount(wallet.getBalance());
        request.setChannel(WalletRefundChannel.MANUAL_ADMIN);
        request.setStatus(WalletRefundRequestStatus.PENDING);
        request.setRequestedAt(LocalDateTime.now(ZoneOffset.UTC));
        WalletRefundRequestEntity saved = refundRequestRepository.save(request);

        auditService.log("wallet_refund_request", saved.getId(), "REQUESTED", userId,
                Map.of("currency", wallet.getCurrency(), "amount", wallet.getBalance().toString()));

        adminAlertService.raise("wallet-refund-requested",
                "Utilisateur bloqué en suppression de compte par un solde wallet non nul",
                Map.of("userId", userId, "currency", wallet.getCurrency(),
                        "amount", wallet.getBalance(), "requestId", saved.getId()));

        return saved;
    }

    @Transactional(readOnly = true)
    public Page<WalletRefundRequestEntity> listPending(Pageable pageable) {
        return refundRequestRepository.findAllByStatusOrderByRequestedAtAsc(
                WalletRefundRequestStatus.PENDING, pageable);
    }

    /**
     * Débite le wallet puis marque le ticket résolu. À appeler par l'admin une fois le
     * remboursement fait manuellement hors-app — cette méthode ne parle jamais à Stripe
     * elle-même.
     *
     * <p>Montant débité selon la nature du ticket :
     * <ul>
     *   <li>ticket racine (sans parent) : le solde RÉEL au moment du clic, pas le montant
     *       snapshoté à la demande, qui a pu bouger entre-temps ;</li>
     *   <li>ticket enfant (ouvert par {@link #openChildForFailedItems} après un échec Stripe
     *       partiel) : {@code min(montant du ticket, solde courant)}. L'enfant ne couvre que
     *       la part cash en échec — le reste du solde peut être du non-cash (parrainage), qui
     *       n'a pas à partir dans un remboursement admin.</li>
     * </ul>
     *
     * <p>{@code debitConfirmedRefund} et non {@code debit} : ce dernier passe par
     * {@code assertNotFrozen}, or le ticket en cours de résolution est lui-même PENDING et
     * gèle donc la devise — tout ticket MANUAL était par construction irrésoluble (422
     * {@code wallet-refund-pending} systématique). {@code debitConfirmedRefund} ignore le gel
     * et audite {@code WALLET_ADMIN_REFUND_OUT}.
     *
     * @throws YadonyBusinessException 422 {@code already-resolved} si le ticket a
     *         déjà été traité (double clic, deux onglets admin).
     */
    @Transactional
    public WalletRefundRequestEntity resolve(UUID requestId, UUID adminId) {
        WalletRefundRequestEntity request = refundRequestRepository.findById(requestId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "wallet-refund-request-not-found", "Not Found", "Demande introuvable"));

        if (request.getStatus() != WalletRefundRequestStatus.PENDING) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "already-resolved",
                    "Unprocessable", "Cette demande a déjà été traitée");
        }

        BigDecimal currentBalance = walletService.getBalance(request.getUserId(), request.getCurrency());
        BigDecimal refundedAmount = currentBalance;
        if (request.getParentRequestId() != null) {
            refundedAmount = request.getAmount().min(currentBalance);
            if (currentBalance.compareTo(request.getAmount()) > 0) {
                log.info("Ticket enfant {} : solde {} superieur au montant du ticket {}, "
                                + "seul ce dernier est debite (le reste n'est pas du cash en echec)",
                        requestId, currentBalance.toPlainString(), request.getAmount().toPlainString());
            }
        }
        if (refundedAmount.signum() > 0) {
            walletService.debitConfirmedRefund(request.getUserId(), request.getCurrency(), refundedAmount,
                    WalletTransactionType.ADMIN_REFUND_OUT);
        }
        if (request.getParentRequestId() != null) {
            settleChildItems(request, refundedAmount);
        }

        request.setStatus(WalletRefundRequestStatus.RESOLVED);
        request.setResolvedAt(LocalDateTime.now(ZoneOffset.UTC));
        request.setResolvedBy(adminId);
        WalletRefundRequestEntity saved = refundRequestRepository.save(request);

        auditService.log("wallet_refund_request", saved.getId(), "RESOLVED", adminId,
                Map.of("userId", request.getUserId(), "currency", request.getCurrency(),
                        "refundedAmount", refundedAmount.toString()));

        return saved;
    }

    /**
     * Répartit le débit d'un ticket enfant sur ses items, dans l'ordre de création : couvert
     * en entier → REFUNDED ; couvert en partie → montant réduit au couvert puis REFUNDED ; non
     * couvert → FAILED {@code balance-short}. La somme des items REFUNDED égale ainsi toujours
     * l'{@code ADMIN_REFUND_OUT}, condition de leur appariement par l'allocateur. Le débit n'est
     * inférieur au ticket que si le solde a baissé entre-temps, quasi impossible puisque le
     * ticket gèle la devise. Un ancien ticket enfant sans items n'a rien à répartir.
     */
    private void settleChildItems(WalletRefundRequestEntity child, BigDecimal debited) {
        List<WalletRefundRequestItemEntity> items = refundRequestItemRepository.findByRefundRequestId(child.getId())
                .stream()
                .filter(i -> i.getStatus() == WalletRefundItemStatus.PENDING)
                .sorted(Comparator.comparing(WalletRefundRequestItemEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        BigDecimal left = debited.max(BigDecimal.ZERO);
        for (WalletRefundRequestItemEntity item : items) {
            if (left.compareTo(item.getAmount()) >= 0) {
                left = left.subtract(item.getAmount());
                item.setStatus(WalletRefundItemStatus.REFUNDED);
            } else if (left.signum() > 0) {
                log.warn("Ticket enfant {} : item {} couvert en partie ({} sur {}), solde insuffisant",
                        child.getId(), item.getId(), left.toPlainString(), item.getAmount().toPlainString());
                item.setAmount(left);
                item.setStatus(WalletRefundItemStatus.REFUNDED);
                left = BigDecimal.ZERO;
            } else {
                log.warn("Ticket enfant {} : item {} ({}) non couvert, solde insuffisant",
                        child.getId(), item.getId(), item.getAmount().toPlainString());
                item.setStatus(WalletRefundItemStatus.FAILED);
                item.setFailureReason("balance-short");
            }
            refundRequestItemRepository.save(item);
        }
    }
}
