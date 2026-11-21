package com.yadony.api.payments.fee;

import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.wallet.fees.StripeFeeSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

/**
 * Règlement de la retenue d'annulation d'un expéditeur fautif, rail carte : rassemble les entrées du
 * calcul (montant payé, taux dû, plafond converti, frais Stripe perdus, commission) et les confie à
 * {@link CancellationFeeCalculator}, puis enregistre l'issue sur la ligne de la retenue par le port
 * {@link CancellationFeeRatePort}.
 *
 * <p>Appelé par {@code RefundProcessor} sous le claim du paiement ({@code markRefundedIfEscrow}) : ce
 * claim garantit un seul remboursement par paiement, donc un seul règlement de la retenue, et rend sans
 * danger la lecture non verrouillée du taux.
 */
@Component
public class CancellationFeeSettlement {

    /** Motifs de clôture {@code NOT_APPLICABLE} (audit). */
    static final String NOT_CAPTURED = "PAYMENT_NOT_CAPTURED";
    static final String INTENT_CANCELED = "PAYMENT_INTENT_CANCELED";
    static final String LEGACY_DESTINATION_CHARGE = "LEGACY_DESTINATION_CHARGE";
    static final String ALREADY_PARTIALLY_REFUNDED = "PAYMENT_ALREADY_PARTIALLY_REFUNDED";

    private static final String SUCCEEDED = "succeeded";
    private static final String CANCELED = "canceled";

    /** Taux effectif de commission tiré du paiement : 6 décimales, comme {@code bids.commission_rate}. */
    private static final int RATE_SCALE = 6;

    private final CancellationFeeRatePort port;
    private final PaymentRepository payments;
    private final StripeFeeSource stripeFees;
    private final CommissionRateResolver commissionRates;
    private final ExchangeRateService exchangeRates;
    private final ApplicationEventPublisher events;

    public CancellationFeeSettlement(CancellationFeeRatePort port, PaymentRepository payments,
                                     StripeFeeSource stripeFees, CommissionRateResolver commissionRates,
                                     ExchangeRateService exchangeRates, ApplicationEventPublisher events) {
        this.port = port;
        this.payments = payments;
        this.stripeFees = stripeFees;
        this.commissionRates = commissionRates;
        this.exchangeRates = exchangeRates;
        this.events = events;
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
                .flatMap(port::dueRate)
                .map(rate -> compute(payment, senderId, travelerId, rate));
    }

    /**
     * Calcul unique de la retenue au taux donné, sans effet de bord (hors lecture des frais Stripe) :
     * le remboursement et l'aperçu montré à l'expéditeur avant d'annuler doivent passer par ici.
     */
    public CancellationFeeBreakdown compute(PaymentEntity payment, UUID senderId, UUID travelerId, BigDecimal rate) {
        String currency = payment.getCurrency();
        BigDecimal paid = payment.getAmount();
        // Vers le bas : le plafond converti ne dépasse jamais son équivalent en euros.
        BigDecimal cap = exchangeRates.convert(port.capEur(), "EUR", currency, RoundingMode.DOWN);
        return CancellationFeeCalculator.compute(paid, rate, cap, refundFees(payment), commissionRate(payment,
                senderId, travelerId), SupportedCurrency.fromCodeOrDefault(currency).minorUnit());
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
        // Lecture simple d'abord : le remboursement ordinaire, sans retenue, ne pose aucun verrou de plus.
        String motive = reason;
        bidIdOf(payment)
                .filter(bidId -> port.dueRate(bidId).isPresent())
                .ifPresent(bidId -> port.markNotApplicable(bidId, motive));
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

    /** Colis du paiement : le sien, ou celui matérialisé depuis le fil de négociation payé. */
    private Optional<UUID> bidIdOf(PaymentEntity payment) {
        if (payment.getBidId() != null) {
            return Optional.of(payment.getBidId());
        }
        return Optional.ofNullable(payment.getNegotiationThreadId()).flatMap(payments::findBidIdLinkedToThread);
    }

    /** Frais Stripe réels de la charge, perdus au remboursement ; repli configuré s'ils sont illisibles. */
    private BigDecimal refundFees(PaymentEntity payment) {
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
