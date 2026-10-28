package com.yadony.api.payments.wallet;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Litige carte (chargeback) sur une RECHARGE du portefeuille. Le titulaire de la carte conteste
 * le paiement auprès de sa banque, qui reprend l'argent à Yadony : sans réaction, le solde
 * crédité restait dépensable (carte volée : recharge, commission réglée, puis contestation).
 *
 * <p>Décision du propriétaire (28/10) : à l'ouverture, le montant contesté est repris dans la
 * limite du solde ({@link WalletTransactionType#TOPUP_DISPUTED}) et la devise reste gelée jusqu'à
 * la clôture ({@link WalletService#isFrozen}) ; l'admin est alerté avec le reste dû. Litige gagné :
 * ce qui avait été repris est rendu ({@link WalletTransactionType#TOPUP_DISPUTE_REVERSED}). Litige
 * perdu : le gel tombe, la reprise reste.
 */
@Service
public class WalletDisputeService {

    private static final Logger log = LoggerFactory.getLogger(WalletDisputeService.class);

    private final WalletTransactionRepository walletTransactionRepository;
    private final WalletDisputeHoldRepository holdRepository;
    private final WalletService walletService;
    private final AuditService auditService;
    private final AdminAlertService adminAlertService;

    public WalletDisputeService(WalletTransactionRepository walletTransactionRepository,
                                WalletDisputeHoldRepository holdRepository,
                                WalletService walletService,
                                AuditService auditService,
                                AdminAlertService adminAlertService) {
        this.walletTransactionRepository = walletTransactionRepository;
        this.holdRepository = holdRepository;
        this.walletService = walletService;
        this.auditService = auditService;
        this.adminAlertService = adminAlertService;
    }

    /**
     * Ouverture d'un litige. Rejouable : un litige déjà enregistré n'est jamais repris deux fois
     * (clé {@code dispute-<id>} et contrainte unique sur le litige).
     *
     * @param amountMinor montant contesté en unité mineure Stripe
     * @return {@code true} si le paiement contesté est une recharge du portefeuille
     */
    @Transactional
    public boolean onDisputeOpened(String disputeId, String paymentIntentId, long amountMinor) {
        if (disputeId == null || paymentIntentId == null || paymentIntentId.isBlank()) {
            return false;
        }
        List<WalletTransactionEntity> topups =
                walletTransactionRepository.findByPaymentRefAndType(paymentIntentId, WalletTransactionType.TOP_UP);
        if (topups.isEmpty()) {
            return false;
        }
        if (holdRepository.findByStripeDisputeId(disputeId).isPresent()) {
            return true;
        }
        WalletTransactionEntity topup = topups.get(0);
        String currency = topup.getCurrency();
        int scale = SupportedCurrency.fromCodeOrDefault(currency).minorUnit();
        BigDecimal disputed = amountMinor > 0
                ? BigDecimal.valueOf(amountMinor, scale).min(topup.getAmount())
                : topup.getAmount();

        BigDecimal debited = walletService.debitDisputedTopup(topup.getUserId(), currency, disputed,
                paymentIntentId, "dispute-" + disputeId);

        WalletDisputeHoldEntity hold = new WalletDisputeHoldEntity();
        hold.setUserId(topup.getUserId());
        hold.setCurrency(currency);
        hold.setStripeDisputeId(disputeId);
        hold.setPaymentIntentId(paymentIntentId);
        hold.setDisputedAmount(disputed);
        hold.setDebitedAmount(debited);
        holdRepository.save(hold);

        BigDecimal remainingDue = disputed.subtract(debited);
        auditService.log("wallet_dispute", hold.getId(), "WALLET_TOPUP_DISPUTE_OPENED", topup.getUserId(),
                Map.of("disputeId", disputeId, "paymentIntentId", paymentIntentId, "currency", currency,
                        "disputed", disputed.toPlainString(), "debited", debited.toPlainString(),
                        "remainingDue", remainingDue.toPlainString()));
        adminAlertService.raise("wallet-topup-disputed",
                remainingDue.signum() > 0
                        ? "Recharge contestée : solde gelé, montant non couvert par le solde à récupérer"
                        : "Recharge contestée : montant repris sur le solde, devise gelée jusqu'à la clôture",
                Map.of("userId", String.valueOf(topup.getUserId()), "disputeId", disputeId,
                        "currency", currency, "disputed", disputed.toPlainString(),
                        "debited", debited.toPlainString(), "remainingDue", remainingDue.toPlainString()));
        log.warn("Litige {} sur la recharge {} : {} {} repris sur {} contestés", disputeId, paymentIntentId,
                debited, currency, disputed);
        return true;
    }

    /**
     * Clôture d'un litige : le gel tombe ; s'il est gagné, la reprise est rendue. La ligne du
     * litige est verrouillée et son statut testé : un webhook rejoué ne recrédite jamais deux fois.
     */
    @Transactional
    public void onDisputeClosed(String disputeId, boolean won) {
        WalletDisputeHoldEntity hold = holdRepository.findByStripeDisputeIdForUpdate(disputeId).orElse(null);
        if (hold == null || hold.getStatus() != WalletDisputeHoldStatus.OPEN) {
            return;
        }
        hold.setStatus(won ? WalletDisputeHoldStatus.WON : WalletDisputeHoldStatus.LOST);
        hold.setClosedAt(LocalDateTime.now(ZoneOffset.UTC));
        holdRepository.save(hold);
        if (won && hold.getDebitedAmount().signum() > 0) {
            walletService.credit(hold.getUserId(), hold.getCurrency(), hold.getDebitedAmount(),
                    WalletTransactionType.TOPUP_DISPUTE_REVERSED, hold.getPaymentIntentId(),
                    "dispute-won-" + disputeId);
        }
        auditService.log("wallet_dispute", hold.getId(),
                won ? "WALLET_TOPUP_DISPUTE_WON" : "WALLET_TOPUP_DISPUTE_LOST", hold.getUserId(),
                Map.of("disputeId", disputeId, "currency", hold.getCurrency(),
                        "debited", hold.getDebitedAmount().toPlainString()));
        adminAlertService.raise("wallet-topup-dispute-closed",
                won ? "Litige de recharge gagné : la reprise est rendue et le solde dégelé"
                        : "Litige de recharge perdu : le solde est dégelé, la reprise reste",
                Map.of("userId", String.valueOf(hold.getUserId()), "disputeId", disputeId,
                        "currency", hold.getCurrency(), "debited", hold.getDebitedAmount().toPlainString()));
    }
}
