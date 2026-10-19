package com.yadony.api.payments.supplement;

import com.stripe.exception.StripeException;
import com.stripe.param.PaymentIntentCreateParams;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.StripeUnavailable;
import com.yadony.api.matching.dto.BidSupplementResponse;
import com.yadony.api.payments.PaymentBusy;
import com.yadony.api.payments.PaymentService;
import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;
import com.yadony.api.payments.supplement.dto.SupplementAcceptResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rail carte du supplément (plan §4.2) : PaymentIntent en capture AUTOMATIQUE sur le compte
 * plateforme, client Stripe de l'expéditeur, sans {@code transfer_data} ni
 * {@code application_fee_amount} (separate charges & transfers, comme le principal). Ses
 * métadonnées ne portent ni {@code bid_id} ni {@code payment_id} : aucun traitement existant
 * (séquestre, commission espèces, recharge du solde) ne la prend pour la sienne.
 *
 * <p>Aucun appel Stripe sous le verrou du colis : {@link #accept} ne fait que passer la demande en
 * {@code PAYMENT_PENDING} ; la création ou la réutilisation du PaymentIntent a lieu dans
 * {@link #afterAcceptCommit}, après le commit, et son résultat est enregistré dans une transaction
 * courte ({@link BidSupplementCardLedger}).
 */
@Component
public class BidSupplementCardRail implements BidSupplementRailHandler {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementCardRail.class);

    /**
     * Moyens du paiement principal ({@code PaymentService.createEscrow}) : la carte (qui porte Apple
     * Pay et Google Pay), plus PayPal seulement si {@code payments_paypal_enabled} est vrai — « paypal »
     * en dur faisait refuser le PaymentIntent par Stripe live (YADONY-FLUTTER-PROD-11).
     */
    static final List<String> PAYMENT_METHOD_TYPES = List.of("card", "paypal");
    static final List<String> CARD_ONLY = List.of("card");

    static final String SCOPE = "BID_SUPPLEMENT";

    /** Tours de la boucle de paiement (PaymentIntent annulé puis recréé, course entre deux taps). */
    private static final int MAX_ROUNDS = 3;

    private final StripeSupplementGateway gateway;
    private final BidSupplementCardLedger ledger;
    private final BidSupplementRepository supplements;
    private final UserRepository users;
    private final PaymentService paymentService;
    private final StorageService storage;
    private final BidSupplementWindow window;
    private final String publishableKey;

    public BidSupplementCardRail(StripeSupplementGateway gateway, BidSupplementCardLedger ledger,
                                 BidSupplementRepository supplements, UserRepository users,
                                 PaymentService paymentService, StorageService storage, BidSupplementWindow window,
                                 @Value("${stripe.publishable-key:}") String publishableKey) {
        this.gateway = gateway;
        this.ledger = ledger;
        this.supplements = supplements;
        this.users = users;
        this.paymentService = paymentService;
        this.storage = storage;
        this.window = window;
        this.publishableKey = publishableKey;
    }

    @Override
    public BidSupplementRail rail() {
        return BidSupplementRail.STRIPE;
    }

    @Override
    public SupplementAcceptResponse accept(BidSupplementEntity supplement, UserEntity sender) {
        ledger.startPayment(supplement);
        return response(supplement, null);
    }

    @Override
    public SupplementAcceptResponse afterAcceptCommit(SupplementAcceptResponse prepared) {
        return pay(prepared.supplement().id());
    }

    /**
     * PaymentIntent à présenter dans la PaymentSheet : celui de la tentative en cours s'il est
     * encore payable, sinon un nouveau ({@code supplement-pi-<id>-v<n>}). Un PaymentIntent déjà
     * réussi est enregistré (filet du webhook) et la réponse n'a plus de {@code clientSecret}.
     */
    SupplementAcceptResponse pay(UUID supplementId) {
        for (int round = 0; round < MAX_ROUNDS; round++) {
            BidSupplementEntity s = ledger.find(supplementId).orElseThrow(BidSupplementCardRail::notFound);
            if (BidSupplementStatus.PAID_STATES.contains(s.getStatus())) {
                return response(s, null);
            }
            if (s.getStatus() != BidSupplementStatus.PAYMENT_PENDING) {
                throw notPending();
            }
            String existing = s.getStripePaymentIntentId();
            if (existing != null) {
                PaymentIntentView pi = retrieve(existing);
                if (pi.payable()) {
                    return response(s, pi);
                }
                if (pi.succeeded()) {
                    ledger.markPaid(supplementId, pi.id(), pi.latestCharge(), "accept");
                } else if (pi.canceled()) {
                    ledger.renewAttempt(supplementId, existing);
                } else {
                    throw new YadonyBusinessException(HttpStatus.CONFLICT, "supplement-payment-processing",
                            "Supplement Payment Processing", "Le paiement du supplément est en cours de traitement");
                }
                continue;
            }
            PaymentIntentView created = create(s);
            BidSupplementEntity stored = ledger.storePaymentIntent(supplementId, s.getStripePiAttempt(), created.id());
            if (created.id().equals(stored.getStripePaymentIntentId())) {
                if (stored.getStatus() != BidSupplementStatus.PAYMENT_PENDING) {
                    // Demande close pendant la création (refus, échéance) : rien ne doit rester payable.
                    cancelQuietly(supplementId, created.id());
                    throw notPending();
                }
                return response(stored, created);
            }
            // Une autre tentative a été enregistrée entre-temps : on relit.
        }
        throw PaymentBusy.exception();
    }

    /**
     * Filet de l'app après la PaymentSheet ({@code POST …/confirm-payment}) : relit le
     * PaymentIntent chez Stripe et enregistre le paiement s'il a réussi. Idempotent.
     */
    public BidSupplementResponse confirmPayment(UUID bidId, UUID supplementId, String firebaseUid) {
        UserEntity sender = users.findByFirebaseUid(firebaseUid).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        BidSupplementEntity s = supplements.findByIdAndBidId(supplementId, bidId)
                .orElseThrow(BidSupplementCardRail::notFound);
        if (!sender.getId().equals(s.getSenderId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas partie à ce colis");
        }
        boolean awaitingConfirmation = s.getStatus() == BidSupplementStatus.PAYMENT_PENDING
                || (BidSupplementCardLedger.CLOSED_UNPAID.contains(s.getStatus()) && s.getPaidAt() == null);
        if (s.getRail() == BidSupplementRail.STRIPE && s.getStripePaymentIntentId() != null && awaitingConfirmation) {
            PaymentIntentView pi = retrieve(s.getStripePaymentIntentId());
            if (pi.succeeded()) {
                ledger.markPaid(supplementId, pi.id(), pi.latestCharge(), "confirm-payment");
                s = ledger.find(supplementId).orElse(s);
            }
        }
        return BidSupplementViews.of(s, true, storage, window.now());
    }

    /** Paiement réussi signalé par Stripe (webhook). */
    public BidSupplementCardLedger.PaidOutcome onPaymentSucceeded(UUID supplementId, String paymentIntentId,
                                                                  String chargeId) {
        return ledger.markPaid(supplementId, paymentIntentId, chargeId, "webhook");
    }

    /**
     * Demande close pendant le paiement : le PaymentIntent est annulé pour que l'expéditeur ne
     * puisse plus payer. S'il a réussi entre-temps, le paiement est tardif, donc rendu.
     */
    public void cancelPendingPayment(UUID supplementId) {
        BidSupplementEntity s = ledger.find(supplementId).orElse(null);
        if (s == null || s.getStripePaymentIntentId() == null
                || !BidSupplementCardLedger.CLOSED_UNPAID.contains(s.getStatus()) || s.getPaidAt() != null) {
            return;
        }
        cancelQuietly(supplementId, s.getStripePaymentIntentId());
    }

    private void cancelQuietly(UUID supplementId, String paymentIntentId) {
        try {
            PaymentIntentView canceled = gateway.cancelPaymentIntent(paymentIntentId);
            ledger.auditOnly(supplementId, "BID_SUPPLEMENT_PI_CANCELED",
                    Map.of("piId", paymentIntentId, "piStatus", String.valueOf(canceled.status())));
        } catch (StripeException e) {
            // Annulation refusée : le plus souvent parce que le paiement vient d'aboutir.
            try {
                PaymentIntentView pi = gateway.retrievePaymentIntent(paymentIntentId);
                if (pi.succeeded()) {
                    ledger.markPaid(supplementId, pi.id(), pi.latestCharge(), "cancel-refused");
                } else if (!pi.canceled()) {
                    log.warn("PaymentIntent {} du supplément {} non annulé (statut {}) : le webhook tranchera",
                            paymentIntentId, supplementId, pi.status());
                }
            } catch (StripeException retrieveError) {
                log.error("PaymentIntent {} du supplément {} : annulation et relecture en échec",
                        paymentIntentId, supplementId, retrieveError);
            }
        }
    }

    private PaymentIntentView create(BidSupplementEntity s) {
        UserEntity sender = users.findById(s.getSenderId()).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Expéditeur introuvable"));
        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(s.getCurrency());
        CurrencyAmount gross = CurrencyAmount.of(s.getGrossAmount(), currency);
        try {
            String customerId = paymentService.ensureStripeCustomer(sender);
            PaymentIntentCreateParams.Builder params = PaymentIntentCreateParams.builder()
                    .setAmount(gross.minor())
                    .setCurrency(currency.code())
                    .setCustomer(customerId)
                    .setStatementDescriptorSuffix("YADONY")
                    .putMetadata("bid_supplement_id", s.getId().toString())
                    .putMetadata("scope", SCOPE)
                    .putMetadata("supplement_bid_id", s.getBidId().toString())
                    // Ni commission ni voyageur : l'expéditeur lit ces métadonnées avec le
                    // client_secret et en déduirait le net. La trace complète est dans l'audit.
                    .putMetadata("sender_id", s.getSenderId().toString())
                    .putMetadata("currency", currency.code());
            boolean paypal = paymentService.paypalEnabled();
            if (paypal) {
                params.setPaymentMethodOptions(PaymentIntentCreateParams.PaymentMethodOptions.builder()
                        .setPaypal(PaymentService.paypalOptions(sender))
                        .build());
            }
            (paypal ? PAYMENT_METHOD_TYPES : CARD_ONLY).forEach(params::addPaymentMethodType);
            return gateway.createPaymentIntent(params.build(), idempotencyKey(s));
        } catch (StripeException e) {
            log.error("Création du PaymentIntent du supplément {} en échec", s.getId(), e);
            if (StripeUnavailable.isNetworkFailure(e)) {
                throw StripeUnavailable.exception();
            }
            throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "supplement-payment-creation-failed",
                    "Payment Error", "Impossible de créer le paiement du supplément. Veuillez réessayer.");
        }
    }

    static String idempotencyKey(BidSupplementEntity s) {
        return "supplement-pi-" + s.getId() + "-v" + s.getStripePiAttempt();
    }

    private PaymentIntentView retrieve(String paymentIntentId) {
        try {
            return gateway.retrievePaymentIntent(paymentIntentId);
        } catch (StripeException e) {
            if (StripeUnavailable.isNetworkFailure(e)) {
                throw StripeUnavailable.exception();
            }
            throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "stripe-error", "Stripe Error",
                    "Impossible de vérifier le paiement auprès de Stripe");
        }
    }

    private SupplementAcceptResponse response(BidSupplementEntity s, PaymentIntentView pi) {
        BidSupplementResponse view = BidSupplementViews.of(s, true, storage, window.now());
        if (pi == null) {
            return new SupplementAcceptResponse(view, BidSupplementRail.STRIPE.name(), null, null,
                    s.getGrossAmount(), s.getCurrency(), publishableKey);
        }
        List<String> types = pi.paymentMethodTypes() == null || pi.paymentMethodTypes().isEmpty()
                ? (paymentService.paypalEnabled() ? PAYMENT_METHOD_TYPES : CARD_ONLY) : pi.paymentMethodTypes();
        return new SupplementAcceptResponse(view, BidSupplementRail.STRIPE.name(), pi.clientSecret(), types,
                s.getGrossAmount(), s.getCurrency(), publishableKey);
    }

    private static YadonyBusinessException notFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "supplement-not-found", "Supplement Not Found",
                "Demande de supplément introuvable");
    }

    private static YadonyBusinessException notPending() {
        return new YadonyBusinessException(HttpStatus.CONFLICT, "supplement-not-pending", "Supplement Not Pending",
                "Cette demande de supplément n'attend plus de paiement");
    }
}
