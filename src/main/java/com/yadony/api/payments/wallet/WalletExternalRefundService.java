package com.yadony.api.payments.wallet;

import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Refund;
import com.stripe.param.RefundListParams;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Recharge carte du portefeuille remboursée HORS de l'app (tableau de bord Stripe, support) : l'argent
 * est déjà revenu sur la carte, mais le solde garde le crédit. Décision du propriétaire (29/10) : la
 * devise est gelée dès le webhook {@code charge.refunded}, sans débit automatique (un ticket manuel
 * clôturé dans l'admin débite déjà le solde : un second débit léserait l'utilisateur), et un admin est
 * alerté. Il régularise par une correction de solde (le gel laisse passer les débits), puis lève le gel.
 */
@Service
public class WalletExternalRefundService {

    private static final Logger log = LoggerFactory.getLogger(WalletExternalRefundService.class);

    static final String ALERT_CODE = "wallet-topup-refunded-externally";
    /** Statuts d'un remboursement dont l'argent est parti ou part vers la carte. */
    private static final Set<String> MONEY_OUT = Set.of("succeeded", "pending", "requires_action");
    static final int NOTE_MAX_LENGTH = 500;

    private final WalletTransactionRepository transactionRepository;
    private final WalletRefundRequestItemRepository refundItemRepository;
    private final WalletExternalRefundHoldRepository holdRepository;
    private final WalletService walletService;
    private final AuditService auditService;
    private final AdminAlertService adminAlertService;

    public WalletExternalRefundService(WalletTransactionRepository transactionRepository,
                                       WalletRefundRequestItemRepository refundItemRepository,
                                       WalletExternalRefundHoldRepository holdRepository,
                                       WalletService walletService,
                                       AuditService auditService,
                                       AdminAlertService adminAlertService) {
        this.transactionRepository = transactionRepository;
        this.refundItemRepository = refundItemRepository;
        this.holdRepository = holdRepository;
        this.walletService = walletService;
        this.auditService = auditService;
        this.adminAlertService = adminAlertService;
    }

    /**
     * Webhook {@code charge.refunded} : pose un gel par remboursement de recharge que l'app n'a pas
     * émis. Idempotent (un gel par identifiant de remboursement Stripe, index unique en base).
     *
     * @return nombre de gels posés par cet appel
     */
    @Transactional
    public int recordFromCharge(Charge charge) {
        String paymentIntentId = charge.getPaymentIntent();
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            return 0;
        }
        List<WalletTransactionEntity> topups =
                transactionRepository.findByPaymentRefAndType(paymentIntentId, WalletTransactionType.TOP_UP);
        if (topups.isEmpty()) {
            return 0;
        }
        WalletTransactionEntity topup = topups.get(0);
        int opened = 0;
        for (Refund refund : refundsOf(charge, paymentIntentId)) {
            if (refund.getId() == null || !MONEY_OUT.contains(refund.getStatus()) || emittedByApp(refund)
                    || holdRepository.existsByStripeRefundId(refund.getId())) {
                continue;
            }
            if (openHold(topup, paymentIntentId, refund)) {
                opened++;
            }
        }
        return opened;
    }

    /** Gels d'un utilisateur, ouverts et levés, du plus récent au plus ancien (fiche admin). */
    @Transactional(readOnly = true)
    public List<WalletExternalRefundHoldEntity> holds(UUID userId) {
        return holdRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Levée d'un gel par un admin, une fois le solde régularisé. Une seule décision par gel.
     *
     * @throws YadonyBusinessException 400 note absente ou trop longue, 404 gel introuvable pour cet
     *         utilisateur, 409 gel déjà levé
     */
    @Transactional
    public WalletExternalRefundHoldEntity release(UUID userId, UUID holdId, UUID adminId, String note) {
        String trimmed = note == null ? "" : note.trim();
        if (trimmed.isEmpty() || trimmed.length() > NOTE_MAX_LENGTH) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST, "wallet-external-refund-hold-note-invalid",
                    "Bad Request", "Indiquez comment le solde a été régularisé (" + NOTE_MAX_LENGTH
                    + " caractères au plus)");
        }
        WalletExternalRefundHoldEntity hold = holdRepository.findById(holdId)
                .filter(h -> h.getUserId().equals(userId))
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "wallet-external-refund-hold-not-found", "Not Found", "Gel introuvable"));
        if (holdRepository.release(holdId, userId, adminId, LocalDateTime.now(ZoneOffset.UTC), trimmed) == 0) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "wallet-external-refund-hold-already-released",
                    "Conflict", "Ce gel a déjà été levé");
        }
        auditService.log("wallet_external_refund_hold", holdId, "WALLET_EXTERNAL_REFUND_HOLD_RELEASED", adminId,
                Map.of("userId", userId.toString(), "currency", hold.getCurrency(),
                        "amount", hold.getAmount().toPlainString(), "note", trimmed));
        return holdRepository.findById(holdId).orElseThrow();
    }

    private boolean openHold(WalletTransactionEntity topup, String paymentIntentId, Refund refund) {
        String currency = topup.getCurrency();
        BigDecimal amount = BigDecimal.valueOf(refund.getAmount() == null ? 0L : refund.getAmount(),
                SupportedCurrency.fromCodeOrDefault(currency).minorUnit());
        if (amount.signum() <= 0) {
            return false;
        }
        WalletExternalRefundHoldEntity hold = new WalletExternalRefundHoldEntity();
        hold.setUserId(topup.getUserId());
        hold.setCurrency(currency);
        hold.setPaymentIntentId(paymentIntentId);
        hold.setStripeRefundId(refund.getId());
        hold.setAmount(amount);
        try {
            hold = holdRepository.saveAndFlush(hold);
        } catch (DataIntegrityViolationException e) {
            // Webhook rejoué en parallèle : l'autre exécution a posé le gel.
            log.info("Gel de remboursement externe {} déjà posé", refund.getId());
            return false;
        }
        BigDecimal balance = walletService.getBalance(topup.getUserId(), currency);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("userId", topup.getUserId().toString());
        context.put("currency", currency);
        context.put("refundedAmount", amount.toPlainString());
        context.put("balance", balance == null ? "0" : balance.toPlainString());
        context.put("stripeRefundId", refund.getId());
        context.put("paymentIntentId", paymentIntentId);
        // Un ticket de remboursement manuel ouvert débitera lui-même le solde à sa résolution : une
        // correction en plus le débiterait deux fois. L'alerte dit donc laquelle des deux voies suivre.
        boolean requestPending = walletService.isRefundRequestPending(topup.getUserId(), currency);
        context.put("refundRequestPending", requestPending);
        auditService.log("wallet_external_refund_hold", hold.getId(), "WALLET_EXTERNAL_REFUND_HOLD_OPENED",
                topup.getUserId(), context);
        adminAlertService.raise(ALERT_CODE, "Recharge du portefeuille remboursée hors de l'app : devise "
                + currency + " gelée. " + (requestPending
                        ? "Une demande de remboursement est en cours sur cette devise : la résoudre (elle débite "
                                + "le solde), sans correction en plus, puis lever le gel."
                        : "Débiter le solde du montant remboursé (correction de solde), puis lever le gel depuis "
                                + "la fiche utilisateur."), context);
        return true;
    }

    /**
     * Remboursements de la charge : la liste embarquée quand Stripe l'envoie, sinon une lecture (les
     * événements récents n'incluent plus {@code charge.refunds}). Une lecture impossible lève : le
     * webhook sera rejoué plutôt que de laisser passer un remboursement externe.
     */
    private List<Refund> refundsOf(Charge charge, String paymentIntentId) {
        if (charge.getRefunds() != null && charge.getRefunds().getData() != null
                && !Boolean.TRUE.equals(charge.getRefunds().getHasMore())) {
            return charge.getRefunds().getData();
        }
        try {
            return Refund.list(RefundListParams.builder().setPaymentIntent(paymentIntentId).setLimit(100L).build())
                    .getData();
        } catch (StripeException e) {
            throw new IllegalStateException("Remboursements de " + paymentIntentId + " illisibles", e);
        }
    }

    /** Remboursement émis par l'app : métadonnée posée à la création (lot A) ou item connu en base. */
    private boolean emittedByApp(Refund refund) {
        Map<String, String> metadata = refund.getMetadata();
        if (metadata != null && metadata.containsKey(WalletSelfRefundService.REFUND_ITEM_METADATA)) {
            return true;
        }
        return refundItemRepository.existsByStripeRefundId(refund.getId());
    }
}
