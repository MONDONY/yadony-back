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

    public WalletRefundRequestService(WalletService walletService,
                                       WalletRefundRequestRepository refundRequestRepository,
                                       AuditService auditService,
                                       AdminAlertService adminAlertService) {
        this.walletService = walletService;
        this.refundRequestRepository = refundRequestRepository;
        this.auditService = auditService;
        this.adminAlertService = adminAlertService;
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
     */
    @Transactional
    public WalletRefundRequestEntity openChildForFailedItems(WalletRefundRequestEntity parent, BigDecimal failedAmount) {
        if (failedAmount == null || failedAmount.signum() <= 0) {
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

        auditService.log("wallet_refund_request", saved.getId(), "MANUAL_CHILD_OPENED", parent.getUserId(),
                Map.of("currency", parent.getCurrency(), "amount", failedAmount.toPlainString(),
                        "parentRequestId", String.valueOf(parent.getId())));
        adminAlertService.raise("wallet-refund-requested",
                "Remboursement Stripe automatique echoue, ticket manuel ouvert",
                Map.of("requestId", String.valueOf(saved.getId()), "parentRequestId", String.valueOf(parent.getId()),
                        "userId", String.valueOf(parent.getUserId()), "currency", parent.getCurrency(),
                        "amount", failedAmount.toPlainString()));
        return saved;
    }

    private WalletRefundRequestEntity requestForCurrency(UUID userId, WalletAccountEntity wallet) {
        WalletRefundRequestEntity existing = refundRequestRepository
                .findByUserIdAndCurrencyAndStatusIn(userId, wallet.getCurrency(),
                        List.of(WalletRefundRequestStatus.PENDING, WalletRefundRequestStatus.PROCESSING))
                .orElse(null);
        if (existing != null) {
            return existing;
        }

        WalletRefundRequestEntity request = new WalletRefundRequestEntity();
        request.setUserId(userId);
        request.setCurrency(wallet.getCurrency());
        request.setAmount(wallet.getBalance());
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

        request.setStatus(WalletRefundRequestStatus.RESOLVED);
        request.setResolvedAt(LocalDateTime.now(ZoneOffset.UTC));
        request.setResolvedBy(adminId);
        WalletRefundRequestEntity saved = refundRequestRepository.save(request);

        auditService.log("wallet_refund_request", saved.getId(), "RESOLVED", adminId,
                Map.of("userId", request.getUserId(), "currency", request.getCurrency(),
                        "refundedAmount", refundedAmount.toString()));

        return saved;
    }
}
