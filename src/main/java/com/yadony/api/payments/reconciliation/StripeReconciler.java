package com.yadony.api.payments.reconciliation;

import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.EscrowCaptureService;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.reconciliation.ReconciliationMismatch.Provider;
import com.yadony.api.payments.wallet.WalletTransactionEntity;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import com.yadony.api.payments.wallet.WalletTransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Rapprochement quotidien avec Stripe : relit chez Stripe ce que la base dit des paiements de
 * colis par carte, des recharges wallet et des commissions prélevées par carte.
 *
 * <p>Correspondance des statuts d'un paiement colis (capture manuelle, encaissement à
 * l'acceptation) : PENDING = pas encore autorisé ; ESCROW = autorisé ({@code requires_capture})
 * ou encaissé ({@code succeeded}) — encore autorisé au-delà de {@link #CAPTURE_GRACE} alors que la
 * capture était due, il est signalé {@code SEQUESTRE_NON_CAPTURE} ; RELEASED = encaissé ; REFUNDED = annulé avant encaissement
 * ou encaissé puis remboursé en totalité ; CANCELLED et FAILED = jamais autorisé ni encaissé.
 *
 * <p>Aucune transaction ouverte ici : les lectures en base sont courtes et les appels Stripe,
 * nombreux, ne doivent pas tenir une connexion du pool. Une erreur Stripe sur un objet est
 * comptée et n'arrête pas les autres ; l'objet est revu au passage suivant.
 */
@Component
public class StripeReconciler {

    private static final Logger log = LoggerFactory.getLogger(StripeReconciler.class);

    /** Paiements colis et commissions relus : tous les ouverts, plus ceux de cette période. */
    static final Duration PAYMENT_WINDOW = Duration.ofDays(30);
    /** Recharges relues : le passage est quotidien, trois jours laissent deux rattrapages. */
    static final Duration TOPUP_WINDOW = Duration.ofDays(3);
    /** Délai laissé aux webhooks avant de conclure qu'un événement Stripe a été manqué. */
    static final Duration WEBHOOK_GRACE = Duration.ofHours(1);
    /**
     * Délai laissé à la capture d'un séquestre carte (listener asynchrone après le passage en
     * séquestre ou l'acceptation du colis) avant de signaler {@code SEQUESTRE_NON_CAPTURE}.
     */
    static final Duration CAPTURE_GRACE = Duration.ofHours(2);
    private static final DateTimeFormatter CAPTURE_BEFORE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private static final String TOPUP_KEY_PREFIX = "stripe-";

    private final PaymentRepository payments;
    private final WalletTransactionRepository ledger;
    private final BidRepository bids;
    private final StripeLedgerSource stripe;

    public StripeReconciler(PaymentRepository payments, WalletTransactionRepository ledger,
                            BidRepository bids, StripeLedgerSource stripe) {
        this.payments = payments;
        this.ledger = ledger;
        this.bids = bids;
        this.stripe = stripe;
    }

    public ReconciliationResult reconcile(Instant now) {
        Tally tally = new Tally();
        reconcilePayments(now, tally);
        reconcileTopups(now, tally);
        reconcileCommissions(now, tally);
        return new ReconciliationResult(List.copyOf(tally.mismatches), tally.checked, tally.errors);
    }

    // ── Paiements colis ──────────────────────────────────────────────────────

    private void reconcilePayments(Instant now, Tally tally) {
        LocalDateTime since = utc(now.minus(PAYMENT_WINDOW));
        for (PaymentEntity payment : payments.findForStripeReconciliation(
                List.of(PaymentStatus.PENDING, PaymentStatus.ESCROW), since)) {
            PaymentIntent pi;
            try {
                pi = stripe.retrieveWithLatestCharge(payment.getStripePaymentIntentId());
            } catch (StripeException e) {
                tally.error("paiement " + payment.getId(), e);
                continue;
            }
            tally.checked++;
            SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(payment.getCurrency());
            long expected = CurrencyAmount.of(payment.getAmount(), currency).minor();
            List<String> codes = new ArrayList<>();
            if (pi.getAmount() == null || pi.getAmount() != expected
                    || !currency.code().equalsIgnoreCase(pi.getCurrency())) {
                codes.add("MONTANT_DIFFERENT");
            }
            String status = pi.getStatus();
            boolean captured = "succeeded".equals(status);
            boolean authorized = "requires_capture".equals(status);
            long refundedAtStripe = refunded(pi);
            switch (payment.getStatus()) {
                case ESCROW -> {
                    if (!captured && !authorized) codes.add("SEQUESTRE_SANS_FONDS");
                    if (authorized && captureOverdue(payment, now)) codes.add("SEQUESTRE_NON_CAPTURE");
                }
                case RELEASED -> {
                    if (!captured) codes.add("VERSE_SANS_ENCAISSEMENT");
                }
                case REFUNDED -> {
                    boolean refundedAtStripeInFull = "canceled".equals(status)
                            || (captured && refundedAtStripe >= expected);
                    if (!refundedAtStripeInFull) codes.add("REMBOURSEMENT_ABSENT_CHEZ_STRIPE");
                }
                case CANCELLED, FAILED -> {
                    if (captured || authorized) codes.add("ENCAISSE_MAIS_ANNULE");
                }
                case PENDING -> {
                    if ((captured || authorized) && createdBefore(payment.getCreatedAt(), now.minus(WEBHOOK_GRACE))) {
                        codes.add("AUTORISE_NON_ENREGISTRE");
                    }
                }
                default -> {
                }
            }
            if (captured && payment.getStatus() != PaymentStatus.REFUNDED) {
                BigDecimal refundedLocally = payment.getRefundedAmount() == null
                        ? BigDecimal.ZERO : payment.getRefundedAmount();
                if (CurrencyAmount.of(refundedLocally, currency).minor() != refundedAtStripe) {
                    codes.add("REMBOURSE_DIFFERENT");
                }
            }
            if (!codes.isEmpty()) {
                tally.mismatch(payment.getId().toString(), codes,
                        "base : " + payment.getStatus() + " " + payment.getAmount() + " " + payment.getCurrency()
                                + " (remboursé " + payment.getRefundedAmount() + ") ; Stripe " + pi.getId() + " : "
                                + status + " " + pi.getAmount() + " " + pi.getCurrency()
                                + " (remboursé " + refundedAtStripe + " en unités mineures)"
                                + (codes.contains("SEQUESTRE_NON_CAPTURE") ? captureDeadline(pi, payment) : ""));
            }
        }
    }

    /**
     * Séquestre carte encore à l'état d'autorisation alors qu'il aurait dû être capturé : modèle
     * non legacy (le legacy capture à la livraison), paiement de négociation (capturé au passage
     * en séquestre) ou colis déjà accepté (capturé à l'acceptation), au-delà de
     * {@link #CAPTURE_GRACE}. Un colis classique pas encore accepté reste une autorisation normale.
     * {@code captured_at} n'entre pas en compte : une capture en échec a pu le laisser posé.
     */
    private boolean captureOverdue(PaymentEntity payment, Instant now) {
        Instant limit = now.minus(CAPTURE_GRACE);
        if (payment.isLegacyDestinationCharge() || !createdBefore(payment.getCreatedAt(), limit)) {
            return false;
        }
        if (payment.getBidId() == null) {
            return EscrowCaptureService.captureDue(payment, null);
        }
        return bids.findById(payment.getBidId())
                .filter(bid -> EscrowCaptureService.captureDue(payment, bid.getStatus()))
                .filter(bid -> bid.getUpdatedAt() == null || createdBefore(bid.getUpdatedAt(), limit))
                .isPresent();
    }

    /** Urgence d'un séquestre non capturé : date limite de capture donnée par Stripe. */
    private static String captureDeadline(PaymentIntent pi, PaymentEntity payment) {
        Charge charge = pi.getLatestChargeObject();
        Long before = charge == null || charge.getPaymentMethodDetails() == null
                || charge.getPaymentMethodDetails().getCard() == null
                ? null : charge.getPaymentMethodDetails().getCard().getCaptureBefore();
        String deadline = before == null
                ? "date limite de capture inconnue (autorisation carte ~7 jours)"
                : "à capturer avant le " + CAPTURE_BEFORE_FORMAT.format(Instant.ofEpochSecond(before));
        return " ; séquestre non capturé (captured_at " + payment.getCapturedAt() + "), " + deadline
                + " : capturer à la livraison ou par la libération forcée admin, sinon le voyageur ne sera pas payé";
    }

    // ── Recharges wallet ─────────────────────────────────────────────────────

    private void reconcileTopups(Instant now, Tally tally) {
        Instant since = now.minus(TOPUP_WINDOW);
        Set<String> succeededAtStripe = new HashSet<>();
        List<PaymentIntent> topups;
        try {
            topups = stripe.walletTopupsCreatedSince(since);
        } catch (StripeException e) {
            tally.error("liste des recharges", e);
            topups = List.of();
        }
        for (PaymentIntent pi : topups) {
            if (!"succeeded".equals(pi.getStatus())) {
                continue;
            }
            succeededAtStripe.add(pi.getId());
            tally.checked++;
            Optional<WalletTransactionEntity> credit = ledger.findByIdempotencyKey(TOPUP_KEY_PREFIX + pi.getId());
            if (credit.isEmpty()) {
                if (pi.getCreated() != null && Instant.ofEpochSecond(pi.getCreated()).isBefore(now.minus(WEBHOOK_GRACE))) {
                    tally.mismatch(pi.getId(), List.of("RECHARGE_NON_CREDITEE"),
                            "Stripe : recharge payée " + pi.getAmount() + " " + pi.getCurrency()
                                    + " (utilisateur " + pi.getMetadata().get("user_id") + ") ; base : aucun crédit");
                }
                continue;
            }
            SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(pi.getCurrency());
            BigDecimal paid = BigDecimal.valueOf(pi.getAmount(), currency.minorUnit());
            WalletTransactionEntity tx = credit.get();
            if (paid.compareTo(tx.getAmount()) != 0 || !currency.code().equalsIgnoreCase(tx.getCurrency())) {
                tally.mismatch(pi.getId(), List.of("RECHARGE_MONTANT_DIFFERENT"),
                        "Stripe : " + paid + " " + pi.getCurrency() + " ; base : " + tx.getAmount() + " " + tx.getCurrency());
            }
        }
        // Sens inverse : chaque crédit de recharge récent correspond à un paiement réussi.
        for (WalletTransactionEntity tx : ledger.findByTypeAndIdempotencyKeyStartingWithAndCreatedAtAfter(
                WalletTransactionType.TOP_UP, TOPUP_KEY_PREFIX, since)) {
            String piId = tx.getIdempotencyKey().substring(TOPUP_KEY_PREFIX.length());
            if (succeededAtStripe.contains(piId)) {
                continue;
            }
            PaymentIntent pi;
            try {
                pi = stripe.retrieveWithLatestCharge(piId);
            } catch (StripeException e) {
                tally.error("recharge " + piId, e);
                continue;
            }
            tally.checked++;
            if (!"succeeded".equals(pi.getStatus())) {
                tally.mismatch(piId, List.of("CREDIT_SANS_PAIEMENT"),
                        "base : crédit " + tx.getAmount() + " " + tx.getCurrency() + " ; Stripe : " + pi.getStatus());
            }
        }
    }

    // ── Commissions prélevées par carte ──────────────────────────────────────

    private void reconcileCommissions(Instant now, Tally tally) {
        for (BidEntity bid : bids.findCardCommissionsUpdatedSince(CommissionChargedVia.CARD,
                utc(now.minus(PAYMENT_WINDOW)))) {
            CommissionStatus local = bid.getCommissionStatus();
            if (local != CommissionStatus.CHARGED && local != CommissionStatus.REFUNDED) {
                continue;
            }
            PaymentIntent pi;
            try {
                pi = stripe.retrieveWithLatestCharge(bid.getCommissionPaymentIntentId());
            } catch (StripeException e) {
                tally.error("commission du bid " + bid.getId(), e);
                continue;
            }
            tally.checked++;
            boolean captured = "succeeded".equals(pi.getStatus());
            boolean refundedInFull = "canceled".equals(pi.getStatus())
                    || (captured && pi.getAmount() != null && refunded(pi) >= pi.getAmount());
            String code = null;
            if (local == CommissionStatus.CHARGED && !captured) {
                code = "COMMISSION_NON_ENCAISSEE";
            } else if (local == CommissionStatus.CHARGED && refundedInFull) {
                code = "COMMISSION_REMBOURSEE_CHEZ_STRIPE";
            } else if (local == CommissionStatus.REFUNDED && !refundedInFull) {
                code = "COMMISSION_NON_REMBOURSEE";
            }
            if (code != null) {
                tally.mismatch(bid.getId().toString(), List.of(code),
                        "base : commission " + local + " ; Stripe " + pi.getId() + " : " + pi.getStatus()
                                + " (remboursé " + refunded(pi) + " sur " + pi.getAmount() + ")");
            }
        }
    }

    // ── Outils ───────────────────────────────────────────────────────────────

    /** Montant remboursé de la dernière charge, en unités mineures (0 sans charge). */
    private static long refunded(PaymentIntent pi) {
        Charge charge = pi.getLatestChargeObject();
        return charge == null || charge.getAmountRefunded() == null ? 0L : charge.getAmountRefunded();
    }

    private static boolean createdBefore(LocalDateTime createdAt, Instant limit) {
        return createdAt != null && createdAt.toInstant(ZoneOffset.UTC).isBefore(limit);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static final class Tally {
        final List<ReconciliationMismatch> mismatches = new ArrayList<>();
        int checked;
        int errors;

        void mismatch(String reference, List<String> codes, String detail) {
            mismatches.add(new ReconciliationMismatch(Provider.STRIPE, reference, String.join(",", codes), detail));
        }

        void error(String what, StripeException e) {
            errors++;
            log.warn("Rapprochement Stripe : {} non relu, à revoir au prochain passage ({})", what, e.getMessage());
        }
    }
}
