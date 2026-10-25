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
import java.util.Optional;
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
        } catch (RuntimeException e) {
            // Expéditeur introuvable, client Stripe impossible à préparer… : sans PaymentIntent, la demande ne pourra
            // jamais être autorisée, elle ne doit pas bloquer le colis et ses kilos jusqu'à l'échéance.
            log.error("Préparation de la PaymentIntent de la modification {} en échec", id, e);
            ledger.abandonAuthorization(id);
            throw e;
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

    /**
     * Clé de la capture : {@code amendment-capture-<id>}, puis {@code amendment-capture-<id>-r<n>} après n réponses 5xx
     * de Stripe (une clé rejoue sa première réponse pendant 24 h, erreur 500 comprise). Une coupure réseau garde la clé :
     * Stripe a pu traiter la capture, la même clé rend alors son résultat.
     */
    static String captureKey(UUID amendmentId, int attempt) {
        String key = "amendment-capture-" + amendmentId;
        return attempt == 0 ? key : key + "-r" + attempt;
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
            Optional<BidAmendmentCardLedger.Authorization> authorized =
                    ledger.authorize(amendmentId, pi.id(), pi.status(), "confirm-authorization");
            if (authorized.isPresent()) {
                a = authorized.get().amendment();
                if (authorized.get().releaseAuthorization()) {
                    releaseAuthorization(amendmentId);
                }
            }
        } else if (BidAmendmentCardLedger.awaitsRelease(a)) {
            // Close avant que l'app ne confirme : l'autorisation éventuellement obtenue est rendue (idempotent).
            releaseAuthorization(amendmentId);
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
     * Issue inconnue (réseau) : la demande reste proposée, le voyageur peut réessayer (même clé, même capture). Réponse
     * 5xx de Stripe : l'essai suivant change de clé ({@link #captureKey}), sinon Stripe rejouerait la même erreur 24 h.
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
            pi = gateway.capturePaymentIntent(piId, captureKey(id, a.getCaptureAttempt()));
        } catch (StripeException e) {
            log.warn("Capture de la modification {} refusée par Stripe : {}", id, e.getMessage());
            refused = e;
            if (e.getStatusCode() != null && e.getStatusCode() >= 500) {
                ledger.nextCaptureAttempt(id);
            }
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
            String chargeId = chargeOf(id, pi);
            if (chargeId == null) {
                throw stripeError();
            }
            BidAmendmentCardLedger.CaptureOutcome outcome = ledger.captured(id, pi.id(), chargeId, "accept");
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
     *
     * @return vrai si l'autorisation vient d'être rendue (annulée, ou constatée déjà annulée) ; faux sinon (rien à
     *         rendre, capture tardive, annulation impossible).
     */
    public boolean releaseAuthorization(UUID amendmentId) {
        BidAmendmentEntity a = ledger.find(amendmentId).orElse(null);
        if (a == null || !BidAmendmentCardLedger.awaitsRelease(a)) {
            return false;
        }
        String piId = a.getStripePaymentIntentId();
        try {
            PaymentIntentView canceled = gateway.cancelPaymentIntent(piId);
            ledger.authorizationReleased(amendmentId, canceled == null ? null : canceled.status());
            return true;
        } catch (StripeException refused) {
            PaymentIntentView pi = retrieveOrNull(piId);
            if (pi != null && pi.canceled()) {
                ledger.authorizationReleased(amendmentId, pi.status());
                return true;
            }
            if (pi != null && pi.succeeded()) {
                // Capturée entre-temps : remboursée si la capture s'enregistre ; sinon (charge illisible, capture
                // ignorée) la ligne reste à traiter et passe en fin de lot du filet.
                String chargeId = chargeOf(amendmentId, pi);
                if (chargeId == null || ledger.captured(amendmentId, piId, chargeId, "cancel-refused")
                        == BidAmendmentCardLedger.CaptureOutcome.IGNORED) {
                    ledger.releaseAttempted(amendmentId);
                }
                return false;
            }
            log.error("Autorisation {} de la modification {} : annulation refusée ({})", piId, amendmentId,
                    refused.getMessage());
            ledger.releaseAttempted(amendmentId);
            alerts.raiseOnce("AMEND_PI_UNCANCELED_" + amendmentId,
                    "Autorisation carte " + piId + " de la modification close " + amendmentId
                            + " impossible à annuler : le montant reste bloqué chez l'expéditeur (" + refused.getMessage()
                            + ")",
                    Map.of("amendmentId", amendmentId.toString(), "bidId", a.getBidId().toString(),
                            "paymentIntentId", piId, "error", String.valueOf(refused.getMessage())));
        }
        return false;
    }

    /**
     * Charge d'une PaymentIntent capturée, repère du montant à rembourser ou à verser. Absente de la réponse ou du
     * webhook : relue chez Stripe. Introuvable : alerte, rien n'est enregistré (le webhook ou le filet repasseront).
     */
    private String chargeOf(UUID amendmentId, PaymentIntentView pi) {
        if (pi.latestCharge() != null && !pi.latestCharge().isBlank()) {
            return pi.latestCharge();
        }
        PaymentIntentView reread = retrieveOrNull(pi.id());
        if (reread != null && reread.latestCharge() != null && !reread.latestCharge().isBlank()) {
            return reread.latestCharge();
        }
        log.error("Modification {} : PaymentIntent {} capturée sans charge lisible", amendmentId, pi.id());
        alerts.raiseOnce("AMEND_CAPT_NO_CHARGE_" + amendmentId,
                "Montant de la modification " + amendmentId + " capturé (" + pi.id() + ") sans charge lisible : "
                        + "capture non enregistrée, à vérifier",
                Map.of("amendmentId", amendmentId.toString(), "paymentIntentId", String.valueOf(pi.id())));
        return null;
    }

    // ── Webhooks ─────────────────────────────────────────────────────────────

    /** Webhook d'une PaymentIntent de modification (routé par {@link BidAmendmentStripeWebhookRouter}). */
    public void onPaymentIntentWebhook(String type, UUID amendmentId, String paymentIntentId, String status,
                                       String latestCharge, String failureCode) {
        switch (type) {
            case "payment_intent.amount_capturable_updated" ->
                    ledger.authorize(amendmentId, paymentIntentId, status, "webhook")
                            .filter(BidAmendmentCardLedger.Authorization::releaseAuthorization)
                            .ifPresent(late -> releaseAuthorization(amendmentId));
            case "payment_intent.succeeded" -> {
                String chargeId = chargeOf(amendmentId, new PaymentIntentView(paymentIntentId, status, null, null, null,
                        latestCharge, null));
                if (chargeId != null) {
                    ledger.captured(amendmentId, paymentIntentId, chargeId, "webhook");
                }
            }
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
