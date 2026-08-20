package com.yadony.api.payments.wallet;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ouvre le ticket de remboursement wallet — automatiquement à chaque suppression de compte
 * en solde positif (cf. {@code UserService#openWalletRefundTicketIfNeeded}, jamais bloquant :
 * Apple 5.1.1(v)), ou explicitement via {@code POST /auth/me/wallet-refund-request} pour qui
 * veut être remboursé sans supprimer son compte. Aucun flow de remboursement automatique
 * n'existe côté Stripe pour le wallet : un admin rembourse manuellement hors-app puis résout
 * le ticket, ce qui débite le wallet à zéro.
 */
@Service
public class WalletRefundRequestService {

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

    /**
     * Ouvre un ticket par devise en solde positif. Idempotent : une devise déjà
     * PENDING/PROCESSING n'est pas dupliquée (l'utilisateur peut retaper l'action sans
     * conséquence, ex. après avoir fermé l'app).
     *
     * @throws YadonyBusinessException 422 {@code wallet-balance-empty} si aucun
     *         solde n'est positif (rien à rembourser — l'appelant ne devrait de
     *         toute façon jamais atteindre cet écran dans ce cas).
     */
    @Transactional
    public List<WalletRefundRequestEntity> request(UUID userId) {
        List<WalletAccountEntity> positiveBalances = walletService.getAllBalances(userId).stream()
                .filter(w -> w.getBalance().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        if (positiveBalances.isEmpty()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-balance-empty",
                    "Unprocessable", "Aucun solde à rembourser");
        }

        return positiveBalances.stream()
                .map(wallet -> requestForCurrency(userId, wallet))
                .toList();
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
     * Débite le solde RÉEL au moment du clic (pas le montant snapshoté à la
     * demande, qui a pu légèrement bouger) puis marque le ticket résolu. À
     * appeler par l'admin une fois le remboursement Stripe fait manuellement
     * hors-app — cette méthode ne parle jamais à Stripe elle-même.
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
        if (currentBalance.compareTo(BigDecimal.ZERO) > 0) {
            walletService.debit(request.getUserId(), request.getCurrency(), currentBalance,
                    WalletTransactionType.ADMIN_REFUND_OUT, null);
        }

        request.setStatus(WalletRefundRequestStatus.RESOLVED);
        request.setResolvedAt(LocalDateTime.now(ZoneOffset.UTC));
        request.setResolvedBy(adminId);
        WalletRefundRequestEntity saved = refundRequestRepository.save(request);

        auditService.log("wallet_refund_request", saved.getId(), "RESOLVED", adminId,
                Map.of("userId", request.getUserId(), "currency", request.getCurrency(),
                        "refundedAmount", currentBalance.toString()));

        return saved;
    }
}
