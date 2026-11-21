package com.yadony.api.payments.fee;

import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.wallet.fees.PawapayFeeTable;
import com.yadony.api.payments.wallet.fees.StripeFeeSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Règlement de la retenue d'annulation d'un expéditeur fautif : rassemble les entrées du calcul (montant payé,
 * taux dû, plafond converti, frais perdus du prestataire, commission) et les confie à
 * {@link CancellationFeeCalculator}, puis enregistre l'issue sur la ligne de la retenue par le port
 * {@link CancellationFeeRatePort}. Un seul calcul pour les deux rails ; seule la source des frais perdus change :
 * frais Stripe réels de la charge (carte), barème pawaPay de l'opérateur du dépôt (mobile money).
 *
 * <p>Appelé par {@code RefundProcessor} sous le claim du paiement ({@code markRefundedIfEscrow}) : ce
 * claim garantit un seul remboursement par paiement, donc un seul règlement de la retenue. Il ne sérialise pas une
 * renonciation de l'administration, qui ne touche que la retenue : le taux est donc lu SOUS LE VERROU de la ligne
 * ({@link CancellationFeeRatePort#dueRateForUpdate}), tenu jusqu'au commit du remboursement. Renonciation et règlement
 * ne peuvent plus s'entrelacer : l'un finit, l'autre voit son résultat. Ordre des verrous : paiement puis retenue.
 */
@Component
public class CancellationFeeSettlement {

    /** Motifs de clôture {@code NOT_APPLICABLE} (audit). */
    static final String NOT_CAPTURED = "PAYMENT_NOT_CAPTURED";
    static final String INTENT_CANCELED = "PAYMENT_INTENT_CANCELED";
    static final String LEGACY_DESTINATION_CHARGE = "LEGACY_DESTINATION_CHARGE";
    static final String ALREADY_PARTIALLY_REFUNDED = "PAYMENT_ALREADY_PARTIALLY_REFUNDED";
    static final String FEE_COMPUTATION_FAILED = "FEE_COMPUTATION_FAILED";
    /** Rail mobile money : dépôt encaissé d'un autre montant que le paiement. */
    static final String DEPOSIT_AMOUNT_MISMATCH = "DEPOSIT_AMOUNT_MISMATCH";
    /** Rail mobile money : remboursement d'une tentative précédente déjà en vol, d'un autre montant. */
    static final String REFUND_IN_FLIGHT = "REFUND_ALREADY_IN_FLIGHT";

    private static final String SUCCEEDED = "succeeded";
    private static final String CANCELED = "canceled";

    /** Taux effectif de commission tiré du paiement : 6 décimales, comme {@code bids.commission_rate}. */
    private static final int RATE_SCALE = 6;

    private final CancellationFeeRatePort port;
    private final PaymentRepository payments;
    private final StripeFeeSource stripeFees;
    private final PawapayFeeTable pawapayFees;
    private final CommissionRateResolver commissionRates;
    private final ExchangeRateService exchangeRates;
    private final ApplicationEventPublisher events;

    public CancellationFeeSettlement(CancellationFeeRatePort port, PaymentRepository payments,
                                     StripeFeeSource stripeFees, PawapayFeeTable pawapayFees,
                                     CommissionRateResolver commissionRates, ExchangeRateService exchangeRates,
                                     ApplicationEventPublisher events) {
        this.port = port;
        this.payments = payments;
        this.stripeFees = stripeFees;
        this.pawapayFees = pawapayFees;
        this.commissionRates = commissionRates;
        this.exchangeRates = exchangeRates;
        this.events = events;
    }

    /**
     * Frais du prestataire perdus au remboursement, dans la devise du paiement : seule entrée du calcul propre au
     * rail. {@code refunded} donne, à la demande, le montant rendu à l'expéditeur (payé − brut), connu avant les
     * frais ; le rail carte ne le lit pas.
     */
    @FunctionalInterface
    private interface LostRefundFees {
        BigDecimal of(BigDecimal paid, Supplier<BigDecimal> refunded);
    }

    /**
     * Ventilation de la retenue due sur ce paiement, ou vide : aucune retenue due, ou paiement qui ne
     * peut pas en porter une (charge legacy, déjà partiellement remboursé ; voir {@link #waiveIfDue}).
     *
     * @param senderId   expéditeur, pour le seul repli du taux de commission
     * @param travelerId voyageur, idem
     */
    public Optional<CancellationFeeBreakdown> settle(PaymentEntity payment, UUID senderId, UUID travelerId) {
        if (ineligibility(payment) != null) {
            return Optional.empty();
        }
        return bidIdOf(payment)
                .flatMap(port::dueRateForUpdate)
                .map(rate -> compute(payment, senderId, travelerId, rate));
    }

    /**
     * Rail mobile money : comme {@link #settle}, frais perdus lus dans le barème pawaPay de l'opérateur du dépôt
     * ({@link #computeMobileMoney}). Vide aussi quand le dépôt encaissé n'est pas exactement le montant du paiement :
     * le remboursement porte sur le dépôt, la retenue sur le paiement, l'écart serait perdu ou rendu deux fois.
     *
     * @param depositProvider code pawaPay de l'opérateur du dépôt d'origine
     * @param depositAmount   montant du dépôt {@code COMPLETED} remboursé
     */
    public Optional<CancellationFeeBreakdown> settleMobileMoney(PaymentEntity payment, String depositProvider,
                                                                BigDecimal depositAmount) {
        if (mobileMoneyIneligibility(payment, depositAmount) != null) {
            return Optional.empty();
        }
        return bidIdOf(payment)
                .flatMap(port::dueRateForUpdate)
                .map(rate -> computeMobileMoney(payment, depositProvider, rate));
    }

    /**
     * Calcul unique de la retenue au taux donné, sans effet de bord (hors lecture des frais Stripe) :
     * le remboursement et l'aperçu montré à l'expéditeur avant d'annuler doivent passer par ici.
     */
    public CancellationFeeBreakdown compute(PaymentEntity payment, UUID senderId, UUID travelerId, BigDecimal rate) {
        return compute(payment, senderId, travelerId, rate, (paid, refunded) -> stripeRefundFees(payment));
    }

    /**
     * Même calcul sur le rail mobile money : les frais perdus sont ceux du barème pawaPay de l'opérateur du dépôt
     * ({@link PawapayFeeTable#partialRefundFee}), le dépôt facturé sur l'encaissé, le remboursement sur ce qui est
     * rendu. Repli du taux de commission sans les parties (le paiement mobile money garde toujours sa commission).
     */
    public CancellationFeeBreakdown computeMobileMoney(PaymentEntity payment, String depositProvider, BigDecimal rate) {
        return compute(payment, null, null, rate, (paid, refunded) ->
                pawapayFees.partialRefundFee(depositProvider, paid, refunded.get(), payment.getCurrency()));
    }

    private CancellationFeeBreakdown compute(PaymentEntity payment, UUID senderId, UUID travelerId, BigDecimal rate,
                                             LostRefundFees lostFees) {
        String currency = payment.getCurrency();
        BigDecimal paid = payment.getAmount();
        int scale = SupportedCurrency.fromCodeOrDefault(currency).minorUnit();
        // Vers le bas : le plafond converti ne dépasse jamais son équivalent en euros.
        BigDecimal cap = exchangeRates.convert(port.capEur(), "EUR", currency, RoundingMode.DOWN);
        BigDecimal commissionRate = commissionRate(payment, senderId, travelerId);
        // Le brut, donc le montant rendu à l'expéditeur, ne dépend pas des frais : un premier passage à frais nuls le
        // donne, le rail mobile money facture son remboursement sur ce montant-là. Aucune formule recopiée hors du
        // calculateur.
        BigDecimal fees = lostFees.of(paid, () -> CancellationFeeCalculator
                .compute(paid, rate, cap, BigDecimal.ZERO, commissionRate, scale).senderRefund());
        return CancellationFeeCalculator.compute(paid, rate, cap, fees, commissionRate, scale);
    }

    /**
     * La retenue est réglée (remboursement partiel émis) : ventilation enregistrée, puis événement pour le
     * versement du net au voyageur, après commit.
     */
    public void retain(PaymentEntity payment, CancellationFeeBreakdown fee) {
        UUID bidId = bidIdOf(payment).orElseThrow();
        port.markRetained(bidId, fee, payment.getCurrency());
        events.publishEvent(new CancellationFeeRetainedEvent(bidId));
    }

    /**
     * L'expéditeur a été remboursé en entier : une retenue encore due pour ce colis est close
     * {@code NOT_APPLICABLE} (rien n'est retenu ni versé). Sans effet si aucune n'est due.
     *
     * @param paymentIntentStatus statut du PaymentIntent lu par le remboursement
     */
    public void waiveIfDue(PaymentEntity payment, String paymentIntentStatus) {
        String reason = ineligibility(payment);
        if (reason == null) {
            if (SUCCEEDED.equals(paymentIntentStatus)) {
                // Paiement capturé et éligible : settle() vient de lire le taux, rien n'était dû.
                return;
            }
            reason = CANCELED.equals(paymentIntentStatus) ? INTENT_CANCELED : NOT_CAPTURED;
        }
        closeIfDue(payment, reason);
    }

    /**
     * Le calcul de la retenue a échoué ({@link #settle} a levé) et l'expéditeur a été remboursé en entier :
     * la retenue encore due est close {@code NOT_APPLICABLE}, motif {@value #FEE_COMPUTATION_FAILED}.
     */
    public void waiveAfterFailedComputation(PaymentEntity payment) {
        closeIfDue(payment, FEE_COMPUTATION_FAILED);
    }

    /**
     * Rail mobile money, l'expéditeur a été remboursé du dépôt entier : une retenue encore due que ce paiement ne
     * pouvait pas porter (voir {@link #settleMobileMoney}) est close avec son motif. Sans effet si le paiement était
     * éligible : {@link #settleMobileMoney} vient alors de lire le taux, rien n'était dû.
     */
    public void waiveMobileMoneyIfDue(PaymentEntity payment, BigDecimal depositAmount) {
        String reason = mobileMoneyIneligibility(payment, depositAmount);
        if (reason != null) {
            closeIfDue(payment, reason);
        }
    }

    /**
     * Un remboursement d'une tentative précédente est déjà en vol pour un autre montant que l'expéditeur moins la
     * retenue : il fait foi (jamais un second), la retenue est close {@code NOT_APPLICABLE}, motif
     * {@value #REFUND_IN_FLIGHT}.
     */
    public void waiveAfterRefundInFlight(PaymentEntity payment) {
        closeIfDue(payment, REFUND_IN_FLIGHT);
    }

    /** Paiement jamais encaissé (annulé avant tout dépôt abouti) : retenue close, motif {@value #NOT_CAPTURED}. */
    public void waiveNotCollected(PaymentEntity payment) {
        closeIfDue(payment, NOT_CAPTURED);
    }

    /** Lecture simple d'abord : le remboursement ordinaire, sans retenue, ne pose aucun verrou de plus. */
    private void closeIfDue(PaymentEntity payment, String reason) {
        bidIdOf(payment)
                .filter(bidId -> port.dueRate(bidId).isPresent())
                .ifPresent(bidId -> port.markNotApplicable(bidId, reason));
    }

    /**
     * Raison pour laquelle ce paiement ne peut pas porter de retenue, ou {@code null}. Charge legacy
     * (destination charge) : modèle de versement à part, jamais de remboursement partiel. Déjà
     * partiellement remboursé : seul le reste est remboursable, montant à trancher à la main (même règle
     * que le versement à la livraison).
     */
    private static String ineligibility(PaymentEntity payment) {
        if (payment.isLegacyDestinationCharge()) {
            return LEGACY_DESTINATION_CHARGE;
        }
        if (payment.getRefundedAmount() != null && payment.getRefundedAmount().signum() > 0) {
            return ALREADY_PARTIALLY_REFUNDED;
        }
        return null;
    }

    /** Comme {@link #ineligibility}, plus le dépôt d'un autre montant que le paiement (rail mobile money). */
    private static String mobileMoneyIneligibility(PaymentEntity payment, BigDecimal depositAmount) {
        String reason = ineligibility(payment);
        if (reason != null) {
            return reason;
        }
        if (depositAmount == null || payment.getAmount() == null || depositAmount.compareTo(payment.getAmount()) != 0) {
            return DEPOSIT_AMOUNT_MISMATCH;
        }
        return null;
    }

    /** Colis du paiement : le sien, ou celui matérialisé depuis le fil de négociation payé. */
    private Optional<UUID> bidIdOf(PaymentEntity payment) {
        if (payment.getBidId() != null) {
            return Optional.of(payment.getBidId());
        }
        return Optional.ofNullable(payment.getNegotiationThreadId()).flatMap(payments::findBidIdLinkedToThread);
    }

    /** Frais Stripe réels de la charge, perdus au remboursement ; repli configuré s'ils sont illisibles. */
    private BigDecimal stripeRefundFees(PaymentEntity payment) {
        BigDecimal fee = stripeFees.fee(payment.getStripePaymentIntentId(), payment.getCurrency());
        return fee != null ? fee : stripeFees.fallback(payment.getAmount(), payment.getCurrency());
    }

    /**
     * Taux effectif réellement prélevé sur CE paiement : commission ÷ net ({@code amount − commission}).
     * Il porte déjà code promo, bon de parrainage, taux spécial, grille et minimum, figés au paiement ;
     * plafonné à 1 (minimum de grille au-dessus du net). Repli sur le taux du jour si le paiement n'en
     * garde pas trace.
     */
    private BigDecimal commissionRate(PaymentEntity payment, UUID senderId, UUID travelerId) {
        BigDecimal commission = payment.getCommissionAmount();
        BigDecimal net = commission == null ? null : payment.getAmount().subtract(commission);
        if (net == null || net.signum() <= 0) {
            return commissionRates.resolve(travelerId, senderId);
        }
        return commission.divide(net, RATE_SCALE, RoundingMode.HALF_UP).min(BigDecimal.ONE);
    }
}
