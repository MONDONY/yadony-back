package com.yadony.api.payments.wallet;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import jakarta.persistence.EntityManager;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Tickets MANUAL_ADMIN de remboursement wallet, et file admin de toutes les demandes.
 *
 * <p>Le cas nominal (suppression de compte ou demande depuis le wallet) est un remboursement
 * AUTOMATIQUE Stripe ou pawaPay ({@code WalletSelfRefundService}) : la demande passe en
 * PROCESSING dans la même transaction, puis REFUNDED, sans geste de l'équipe. Un ticket manuel
 * n'est ouvert ici que si l'automatique est impossible (rejeu du ledger incohérent, rails
 * mixtes sur la devise) ou a échoué (ticket enfant des items FAILED) : l'admin rembourse alors
 * hors-app puis résout le ticket, ce qui débite le wallet.
 */
@Service
public class WalletRefundRequestService {

    private static final Logger log = LoggerFactory.getLogger(WalletRefundRequestService.class);

    /** Longueur maximale de la note de résolution, alignée sur le DTO admin. */
    public static final int RESOLUTION_NOTE_MAX_LENGTH = 500;

    private final WalletService walletService;
    private final WalletRefundRequestRepository refundRequestRepository;
    private final AuditService auditService;
    private final AdminAlertService adminAlertService;
    private final WalletRefundRequestItemRepository refundRequestItemRepository;
    private final PawapayOperationRepository pawapayOperationRepository;
    private final EntityManager entityManager;

    public WalletRefundRequestService(WalletService walletService,
                                       WalletRefundRequestRepository refundRequestRepository,
                                       AuditService auditService,
                                       AdminAlertService adminAlertService,
                                       WalletRefundRequestItemRepository refundRequestItemRepository,
                                       PawapayOperationRepository pawapayOperationRepository,
                                       EntityManager entityManager) {
        this.walletService = walletService;
        this.refundRequestRepository = refundRequestRepository;
        this.auditService = auditService;
        this.adminAlertService = adminAlertService;
        this.refundRequestItemRepository = refundRequestItemRepository;
        this.pawapayOperationRepository = pawapayOperationRepository;
        this.entityManager = entityManager;
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
     * PaymentIntent, même montant, MÊME FRAIS, sans {@code stripeRefundId}) : la résolution admin les passe
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
            // Le frais suit le montant : sans lui, l'admin réglerait le brut et le résumé
            // annoncerait frais 0 / net = brut, alors que le rail automatique retenait déjà
            // ce frais sur cette même recharge. Le wallet est débité du BRUT dans les deux
            // cas (cf. resolve), seul le net réellement rendu est amputé du frais.
            item.setFeeAmount(failed.getFeeAmount() == null ? BigDecimal.ZERO : failed.getFeeAmount());
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
                "Solde wallet à rembourser à la main (suppression de compte, rejeu du ledger "
                        + "incohérent ou rails mixtes sur la devise)",
                Map.of("userId", userId, "currency", wallet.getCurrency(),
                        "amount", wallet.getBalance(), "requestId", saved.getId()));

        return saved;
    }

    /**
     * File admin, tous canaux, filtrée par statut (suivi des remboursements automatiques
     * lancés à la suppression de compte, échecs, historique). Plus récentes d'abord ; la
     * file « à traiter » seule reste servie par {@link #listPending}, plus anciennes d'abord.
     */
    @Transactional(readOnly = true)
    public Page<WalletRefundRequestEntity> listByStatuses(
            java.util.Collection<WalletRefundRequestStatus> statuses, Pageable pageable) {
        Pageable sorted = org.springframework.data.domain.PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(),
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "requestedAt"));
        return refundRequestRepository.findAllByStatusIn(statuses, sorted);
    }

    /** File admin cherchée (identifiant ou nom de l'utilisateur), plus récentes d'abord. */
    @Transactional(readOnly = true)
    public Page<WalletRefundRequestEntity> search(java.util.Collection<WalletRefundRequestStatus> statuses,
                                                  UUID id, String like, Pageable pageable) {
        return refundRequestRepository.searchAdmin(
                statuses.stream().map(Enum::name).toList(),
                id == null ? null : id.toString(), like,
                org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
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
        return resolve(requestId, adminId, null, null);
    }

    /**
     * Comme {@link #resolve(UUID, UUID)}, en gardant ce que l'admin a réellement versé hors app.
     *
     * <p>{@code paidAmount} est informatif : le débit du wallet reste celui décrit plus haut.
     * L'utilisateur a demandé le remboursement de tout son solde ; l'écart entre le montant
     * demandé et le montant versé correspond aux frais d'opérateur à sa charge (cas réel : 500
     * XOF demandés, 487 XOF versés, ce que le wallet pawaPay avait encaissé).
     *
     * @param paidAmount montant versé, {@code null} si l'admin ne le précise pas (contrat d'avant)
     * @param note       note libre, {@code null} ou vide si aucune ; 500 caractères au plus
     * @throws YadonyBusinessException 422 {@code wallet-refund-paid-amount-invalid} si le montant
     *         est nul ou négatif, {@code wallet-refund-paid-amount-too-high} s'il dépasse le
     *         montant de la demande, {@code wallet-refund-paid-amount-scale} s'il a plus de
     *         décimales que la devise n'en permet, {@code wallet-refund-note-too-long} si la note
     *         dépasse 500 caractères
     */
    @Transactional
    public WalletRefundRequestEntity resolve(UUID requestId, UUID adminId, BigDecimal paidAmount, String note) {
        // Verrou de ligne puis relecture réelle, comme la relance mobile money
        // (WalletRefundRetryService#retry) : une relance et une résolution manuelle simultanées
        // sont sérialisées, la seconde voit l'état laissé par la première (sinon : versement
        // pawaPay ET paiement hors app, l'utilisateur remboursé deux fois).
        WalletRefundRequestEntity request = refundRequestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "wallet-refund-request-not-found", "Not Found", "Demande introuvable"));
        // findByIdForUpdate rend toujours une entité gérée ; le garde ne sert qu'aux tests dont le
        // dépôt est simulé (entité détachée, que refresh refuserait).
        if (entityManager.contains(request)) {
            entityManager.refresh(request);
        }

        if (request.getStatus() == WalletRefundRequestStatus.PROCESSING || pawapayOperationInFlight(request)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-refund-retry-in-progress",
                    "Unprocessable", "Un remboursement mobile money est en cours pour cette demande : "
                            + "attendez son issue avant de la résoudre à la main");
        }
        if (request.getStatus() != WalletRefundRequestStatus.PENDING) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "already-resolved",
                    "Unprocessable", "Cette demande a déjà été traitée");
        }
        validatePaidAmount(request, paidAmount);
        String resolutionNote = normalizeNote(note);

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
        request.setPaidAmount(paidAmount);
        request.setResolutionNote(resolutionNote);
        WalletRefundRequestEntity saved = refundRequestRepository.save(request);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", request.getUserId());
        payload.put("currency", request.getCurrency());
        payload.put("refundedAmount", refundedAmount.toString());
        if (paidAmount != null) {
            payload.put("paidAmount", paidAmount.toPlainString());
        }
        if (resolutionNote != null) {
            payload.put("note", resolutionNote);
        }
        auditService.log("wallet_refund_request", saved.getId(), "RESOLVED", adminId, payload);

        return saved;
    }

    /**
     * Vrai si un item de la demande porte une opération pawaPay (REFUND ou PAYOUT) encore
     * ouverte, ou illisible : l'argent peut encore partir chez l'opérateur.
     */
    private boolean pawapayOperationInFlight(WalletRefundRequestEntity request) {
        for (WalletRefundRequestItemEntity item : refundRequestItemRepository.findByRefundRequestId(request.getId())) {
            for (UUID operationId : new UUID[] {item.getPawapayRefundId(), item.getPawapayPayoutId()}) {
                if (operationId == null) {
                    continue;
                }
                boolean open = pawapayOperationRepository.findById(operationId)
                        .map(op -> op.getStatus() == null || !op.getStatus().isFinal())
                        .orElse(true);
                if (open) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Le montant versé est strictement positif, ne dépasse pas le montant de la demande et
     * respecte les décimales de la devise (0 pour XOF et XAF, 2 pour EUR) : un « 487,5 XOF »
     * serait impossible à verser et fausserait le rapprochement.
     */
    private static void validatePaidAmount(WalletRefundRequestEntity request, BigDecimal paidAmount) {
        if (paidAmount == null) {
            return;
        }
        if (paidAmount.signum() <= 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "wallet-refund-paid-amount-invalid", "Unprocessable",
                    "Le montant versé doit être strictement positif");
        }
        if (paidAmount.compareTo(request.getAmount()) > 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "wallet-refund-paid-amount-too-high", "Unprocessable",
                    "Le montant versé dépasse le montant de la demande ("
                            + request.getAmount().toPlainString() + " " + request.getCurrency() + ")",
                    Map.of("maxAmount", request.getAmount()));
        }
        int maxDecimals = SupportedCurrency.fromCodeOrDefault(request.getCurrency()).minorUnit();
        if (Math.max(paidAmount.stripTrailingZeros().scale(), 0) > maxDecimals) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "wallet-refund-paid-amount-scale", "Unprocessable",
                    "Le montant versé a trop de décimales pour la devise " + request.getCurrency()
                            + " (" + maxDecimals + " au plus)",
                    Map.of("maxDecimals", maxDecimals));
        }
    }

    private static String normalizeNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.length() > RESOLUTION_NOTE_MAX_LENGTH) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "wallet-refund-note-too-long", "Unprocessable",
                    "La note dépasse " + RESOLUTION_NOTE_MAX_LENGTH + " caractères");
        }
        return trimmed;
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
                // Le frais reporté du parent peut désormais dépasser le montant réduit :
                // le plafonner évite un net négatif dans le résumé de la demande.
                if (item.getFeeAmount() != null && item.getFeeAmount().compareTo(left) > 0) {
                    item.setFeeAmount(left);
                }
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
