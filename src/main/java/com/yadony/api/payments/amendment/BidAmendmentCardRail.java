package com.yadony.api.payments.amendment;

import com.stripe.exception.StripeException;
import com.stripe.param.PaymentIntentCreateParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.StripeUnavailable;
import com.yadony.api.matching.dto.BidAmendmentResponse;
import com.yadony.api.payments.PaymentService;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptRequest;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptResponse;
import com.yadony.api.payments.amendment.dto.AmendmentCreateResponse;
import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rail carte d'une modification : le montant est BLOQUÉ sur la carte de l'expéditeur à la création (PaymentIntent en
 * capture manuelle sur le compte plateforme, ni {@code transfer_data} ni {@code application_fee_amount} : separate
 * charges & transfers, comme le principal), puis DÉBITÉ quand le voyageur accepte.
 * <ol>
 *   <li>Création : {@code AUTHORIZATION_PENDING} ; après le commit, la PaymentIntent est créée (clé
 *       {@code amendment-pi-<id>}) et son {@code clientSecret} rendu à l'app, qui ouvre la PaymentSheet.</li>
 *   <li>Autorisation : {@link #confirmAuthorization} (app) ou webhook {@code amount_capturable_updated} ; PaymentIntent
 *       {@code requires_capture} : la demande passe {@code PROPOSED} et le voyageur est prévenu.</li>
 *   <li>Acceptation : la demande reste {@code PROPOSED} sous les verrous ; après le commit, capture (clé
 *       {@code amendment-capture-<id>}), puis acquise. Capture refusée (autorisation expirée) : {@code CANCELLED}
 *       ({@code CAPTURE_FAILED}), kilos rendus, 409 {@code amendment-capture-failed}.</li>
 *   <li>Clôture sans aboutir : l'autorisation est rendue APRÈS le commit qui clôt ({@link #releaseAuthorization}, par
 *       {@link BidAmendmentCardListener}) : la clôture peut se faire sous la transaction du scan de remise, qui peut
 *       encore échouer. {@link #onClosed} ne fait donc rien.</li>
 * </ol>
 * Jamais d'appel Stripe sous un verrou : chaque issue est enregistrée par {@link BidAmendmentCardLedger}, en
 * transaction courte. Ses métadonnées portent {@code bid_amendment_id}, {@code bid_id} et {@code scope} : le routeur des
 * modifications passe avant tout traitement du colis. Ni net ni commission : l'expéditeur les lirait avec le
 * {@code clientSecret}.
 */
@Component
public class BidAmendmentCardRail implements BidAmendmentRailHandler {

    private static final Logger log = LoggerFactory.getLogger(BidAmendmentCardRail.class);

    /** Moyens de la PaymentSheet, comme le principal : la carte (Apple Pay, Google Pay), et PayPal s'il est activé. */
    static final List<String> PAYMENT_METHOD_TYPES = List.of("card", "paypal");
    static final List<String> CARD_ONLY = List.of("card");

    static final String SCOPE = "BID_AMENDMENT";

    private final StripeAmendmentGateway gateway;
    private final BidAmendmentCardLedger ledger;
    private final BidAmendmentRepository amendments;
    private final BidAmendmentItemRepository amendmentItems;
    private final UserRepository users;
    private final PaymentService paymentService;
    private final BidAmendmentWindow window;
    private final AdminAlertEscalator alerts;
    private final String publishableKey;

    public BidAmendmentCardRail(StripeAmendmentGateway gateway, BidAmendmentCardLedger ledger,
                                BidAmendmentRepository amendments, BidAmendmentItemRepository amendmentItems,
                                UserRepository users, PaymentService paymentService, BidAmendmentWindow window,
                                AdminAlertEscalator alerts, @Value("${stripe.publishable-key:}") String publishableKey) {
        this.gateway = gateway;
        this.ledger = ledger;
        this.amendments = amendments;
        this.amendmentItems = amendmentItems;
        this.users = users;
        this.paymentService = paymentService;
        this.window = window;
        this.alerts = alerts;
        this.publishableKey = publishableKey;
    }

    @Override
    public BidSupplementRail rail() {
        return BidSupplementRail.STRIPE;
    }

    // ── Création : montant bloqué ────────────────────────────────────────────

    /** Le montant doit être bloqué avant que le voyageur ne voie la demande. */
    @Override
    public CreateOutcome onCreated(BidAmendmentEntity amendment, UserEntity sender) {
        return CreateOutcome.AUTHORIZATION_PENDING;
    }

    /**
     * Après le commit de la création : PaymentIntent en capture manuelle, brut en unité mineure, devise du colis.
     * Création impossible : la demande est annulée tout de suite (kilos rendus) et l'erreur rendue ; une PaymentIntent
     * créée malgré une coupure reste sans {@code clientSecret} connu de l'app, donc jamais confirmée.
     */
    @Override
    public AmendmentCreateResponse afterCreateCommit(AmendmentCreateResponse created) {
        UUID id = created.amendment().id();
        BidAmendmentEntity a = ledger.find(id).orElseThrow(BidAmendmentCardRail::notFound);
        if (a.getStatus() != BidAmendmentStatus.AUTHORIZATION_PENDING) {
            return created;
        }
        PaymentIntentView pi;
        try {
            pi = createPaymentIntent(a);
        } catch (StripeException e) {
            log.error("Création de la PaymentIntent de la modification {} en échec", id, e);
            ledger.abandonAuthorization(id);
            if (StripeUnavailable.isNetworkFailure(e)) {
                throw StripeUnavailable.exception();
            }
            throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "amendment-payment-creation-failed",
                    "Payment Error", "Impossible de bloquer le montant de la modification. Veuillez réessayer.");
        }
        BidAmendmentEntity stored = ledger.storePaymentIntent(id, pi.id()).orElseThrow(BidAmendmentCardRail::notFound);
        if (stored.getStatus() != BidAmendmentStatus.AUTHORIZATION_PENDING) {
            // Demande close pendant la création (retirée, colis terminé) : rien ne doit rester autorisable.
            releaseAuthorization(id);
            throw notPending();
        }
        List<String> types = pi.paymentMethodTypes() == null || pi.paymentMethodTypes().isEmpty()
                ? (paymentService.paypalEnabled() ? PAYMENT_METHOD_TYPES : CARD_ONLY) : pi.paymentMethodTypes();
        return new AmendmentCreateResponse(view(stored, true), pi.clientSecret(), types, publishableKey);
    }

    private PaymentIntentView createPaymentIntent(BidAmendmentEntity a) throws StripeException {
        UserEntity sender = users.findById(a.getSenderId()).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Expéditeur introuvable"));
        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(a.getCurrency());
        CurrencyAmount gross = CurrencyAmount.of(a.getGrossAmount(), currency);
        PaymentIntentCreateParams.Builder params = PaymentIntentCreateParams.builder()
                .setAmount(gross.minor())
                .setCurrency(currency.code())
                .setCustomer(paymentService.ensureStripeCustomer(sender))
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)
                .setStatementDescriptorSuffix("YADONY")
                .putMetadata("bid_amendment_id", a.getId().toString())
                .putMetadata("bid_id", a.getBidId().toString())
                .putMetadata("scope", SCOPE);
        boolean paypal = paymentService.paypalEnabled();
        if (paypal) {
            params.setPaymentMethodOptions(PaymentIntentCreateParams.PaymentMethodOptions.builder()
                    .setPaypal(PaymentService.paypalOptions(sender))
                    .build());
        }
        (paypal ? PAYMENT_METHOD_TYPES : CARD_ONLY).forEach(params::addPaymentMethodType);
        return gateway.createPaymentIntent(params.build(), paymentIntentKey(a.getId()));
    }

    static String paymentIntentKey(UUID amendmentId) {
        return "amendment-pi-" + amendmentId;
    }

    static String captureKey(UUID amendmentId) {
        return "amendment-capture-" + amendmentId;
    }

    // ── Autorisation confirmée ───────────────────────────────────────────────

    /**
     * Après la PaymentSheet ({@code POST …/confirm-authorization}) : relit la PaymentIntent chez Stripe ; autorisée, la
     * demande est présentée au voyageur. Pas encore autorisée : rien ne change. Idempotent ; vue de l'expéditeur.
     */
    public BidAmendmentResponse confirmAuthorization(UUID bidId, UUID amendmentId, String firebaseUid) {
        UserEntity sender = users.findByFirebaseUid(firebaseUid).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        BidAmendmentEntity a = amendments.findById(amendmentId).filter(found -> found.getBidId().equals(bidId))
                .orElseThrow(BidAmendmentCardRail::notFound);
        if (!sender.getId().equals(a.getSenderId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas partie à cette modification");
        }
        if (a.getRail() != BidSupplementRail.STRIPE) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "amendment-rail-unavailable",
                    "Amendment Rail Unavailable", "Cette modification n'a pas de montant carte à confirmer");
        }
        if (a.getStatus() == BidAmendmentStatus.AUTHORIZATION_PENDING && a.getStripePaymentIntentId() != null) {
            PaymentIntentView pi = retrieve(a.getStripePaymentIntentId());
            a = ledger.authorize(amendmentId, pi.id(), pi.status(), "confirm-authorization").orElse(a);
        }
        return view(a, true);
    }

    // ── Acceptation : capture ────────────────────────────────────────────────

    /**
     * Acceptation par le voyageur, sous les verrous : la demande reste {@code PROPOSED}, aucun appel Stripe. La capture
     * a lieu dans {@link #afterAcceptCommit}.
     */
    @Override
    public AmendmentAcceptResponse accept(BidAmendmentEntity a, UserEntity traveler, AmendmentAcceptRequest request) {
        if (a.getStripePaymentIntentId() == null) {
            // Proposée sans PaymentIntent : impossible par construction (présentée seulement une fois autorisée).
            throw notPending();
        }
        return new AmendmentAcceptResponse(view(a, false), null);
    }

    /**
     * Après le commit de l'acceptation : capture intégrale, puis la modification est acquise sous les verrous. Capture
     * refusée par Stripe : la PaymentIntent est relue ; capturée quand même (rejeu, course), elle compte ; plus
     * capturable (expirée, annulée), la demande est annulée et le voyageur reçoit 409 {@code amendment-capture-failed}.
     * Issue inconnue (réseau) : la demande reste proposée, le voyageur peut réessayer (même clé, même capture).
     */
    @Override
    public AmendmentAcceptResponse afterAcceptCommit(AmendmentAcceptResponse accepted) {
        UUID id = accepted.amendment().id();
        BidAmendmentEntity a = ledger.find(id).orElseThrow(BidAmendmentCardRail::notFound);
        if (a.getStatus().isSecured()) {
            return new AmendmentAcceptResponse(view(a, false), null);
        }
        if (a.getStatus() != BidAmendmentStatus.PROPOSED || a.getStripePaymentIntentId() == null) {
            throw notPending();
        }
        String piId = a.getStripePaymentIntentId();
        PaymentIntentView pi;
        StripeException refused = null;
        try {
            pi = gateway.capturePaymentIntent(piId, captureKey(id));
        } catch (StripeException e) {
            log.warn("Capture de la modification {} refusée par Stripe : {}", id, e.getMessage());
            refused = e;
            pi = retrieveOrNull(piId);
        }
        if (pi == null || (!pi.succeeded() && (BidAmendmentCardLedger.REQUIRES_CAPTURE.equals(pi.status())
                || "processing".equals(pi.status())))) {
            // Issue inconnue ou encore capturable (refus passager) : la demande reste proposée, le voyageur peut
            // réessayer, même clé.
            if (refused == null || StripeUnavailable.isNetworkFailure(refused)) {
                throw StripeUnavailable.exception();
            }
            throw stripeError();
        }
        if (pi.succeeded()) {
            BidAmendmentCardLedger.CaptureOutcome outcome = ledger.captured(id, pi.id(), pi.latestCharge(), "accept");
            if (outcome == BidAmendmentCardLedger.CaptureOutcome.LATE_REFUND) {
                throw notPending();
            }
            BidAmendmentEntity reloaded = ledger.find(id).orElseThrow(BidAmendmentCardRail::notFound);
            if (!reloaded.getStatus().isSecured()) {
                throw notPending();
            }
            return new AmendmentAcceptResponse(view(reloaded, false), null);
        }
        ledger.captureFailed(id, "status-" + pi.status());
        throw new YadonyBusinessException(HttpStatus.CONFLICT, "amendment-capture-failed", "Amendment Capture Failed",
                "Le montant de cette modification n'a pas pu être débité : la demande est annulée");
    }

    // ── Clôture : autorisation rendue ────────────────────────────────────────

    /**
     * Après le commit d'une clôture (refus, retrait, échéance, colis terminé, capture refusée) : la PaymentIntent non
     * capturée est annulée, l'autorisation rendue à l'expéditeur. Déjà annulée : rien. Annulation refusée : la
     * PaymentIntent est relue ; capturée entre-temps, le montant est remboursé (capture tardive) ; encore vivante ou
     * illisible, une alerte admin part.
     */
    public void releaseAuthorization(UUID amendmentId) {
        BidAmendmentEntity a = ledger.find(amendmentId).orElse(null);
        if (a == null || a.getRail() != BidSupplementRail.STRIPE || a.getStatus().isOpen()
                || a.getStripePaymentIntentId() == null || a.getStripeChargeId() != null) {
            return;
        }
        String piId = a.getStripePaymentIntentId();
        try {
            PaymentIntentView canceled = gateway.cancelPaymentIntent(piId);
            ledger.auditOnly(amendmentId, "BID_AMENDMENT_AUTHORIZATION_RELEASED",
                    Map.of("piId", piId, "piStatus", String.valueOf(canceled == null ? null : canceled.status())));
        } catch (StripeException refused) {
            PaymentIntentView pi = retrieveOrNull(piId);
            if (pi != null && pi.canceled()) {
                return;
            }
            if (pi != null && pi.succeeded()) {
                ledger.captured(amendmentId, piId, pi.latestCharge(), "cancel-refused");
                return;
            }
            log.error("Autorisation {} de la modification {} : annulation refusée ({})", piId, amendmentId,
                    refused.getMessage());
            alerts.raiseOnce("AMEND_PI_UNCANCELED_" + amendmentId,
                    "Autorisation carte " + piId + " de la modification close " + amendmentId
                            + " impossible à annuler : le montant reste bloqué chez l'expéditeur (" + refused.getMessage()
                            + ")",
                    Map.of("amendmentId", amendmentId.toString(), "bidId", a.getBidId().toString(),
                            "paymentIntentId", piId, "error", String.valueOf(refused.getMessage())));
        }
    }

    // ── Webhooks ─────────────────────────────────────────────────────────────

    /** Webhook d'une PaymentIntent de modification (routé par {@link BidAmendmentStripeWebhookRouter}). */
    public void onPaymentIntentWebhook(String type, UUID amendmentId, String paymentIntentId, String status,
                                       String latestCharge, String failureCode) {
        switch (type) {
            case "payment_intent.amount_capturable_updated" ->
                    ledger.authorize(amendmentId, paymentIntentId, status, "webhook");
            case "payment_intent.succeeded" -> ledger.captured(amendmentId, paymentIntentId, latestCharge, "webhook");
            case "payment_intent.canceled" -> ledger.paymentIntentCanceled(amendmentId, paymentIntentId);
            // Carte refusée ou 3DS échouée dans la PaymentSheet : l'expéditeur peut réessayer avec la même
            // PaymentIntent ; la demande attend jusqu'à la fin de la fenêtre d'autorisation.
            case "payment_intent.payment_failed" -> ledger.auditOnly(amendmentId,
                    "BID_AMENDMENT_AUTHORIZATION_FAILED",
                    Map.of("piId", String.valueOf(paymentIntentId), "code", String.valueOf(failureCode)));
            default -> log.debug("Webhook {} de la modification {} : rien à faire", type, amendmentId);
        }
    }

    // ── Aides ────────────────────────────────────────────────────────────────

    private PaymentIntentView retrieve(String paymentIntentId) {
        try {
            return gateway.retrievePaymentIntent(paymentIntentId);
        } catch (StripeException e) {
            if (StripeUnavailable.isNetworkFailure(e)) {
                throw StripeUnavailable.exception();
            }
            throw stripeError();
        }
    }

    private PaymentIntentView retrieveOrNull(String paymentIntentId) {
        try {
            return gateway.retrievePaymentIntent(paymentIntentId);
        } catch (StripeException e) {
            return null;
        }
    }

    private BidAmendmentResponse view(BidAmendmentEntity a, boolean forSender) {
        return BidAmendmentViews.of(a, amendmentItems.findByAmendmentId(a.getId()), forSender, window);
    }

    private static YadonyBusinessException stripeError() {
        return new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "stripe-error", "Stripe Error",
                "Impossible de vérifier le paiement auprès de Stripe");
    }

    private static YadonyBusinessException notFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "amendment-not-found", "Amendment Not Found",
                "Modification introuvable");
    }

    private static YadonyBusinessException notPending() {
        return new YadonyBusinessException(HttpStatus.CONFLICT, "amendment-not-pending", "Amendment Not Pending",
                "Cette modification n'attend plus de réponse");
    }
}
