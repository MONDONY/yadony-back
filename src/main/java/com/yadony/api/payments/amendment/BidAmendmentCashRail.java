package com.yadony.api.payments.amendment;

import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.param.PaymentIntentCreateParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.StripeUnavailable;
import com.yadony.api.matching.dto.BidAmendmentResponse;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptRequest;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptResponse;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionSource;
import com.yadony.api.payments.cash.CommissionSplit;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.CommissionUnfundedProblem;
import com.yadony.api.payments.cash.WalletCommissionCollector;
import com.yadony.api.payments.cash.exception.CommissionMethodMissingException;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.supplement.StripeSupplementGateway;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;
import com.yadony.api.payments.wallet.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Rail espèces d'une modification : le complément se remet en main propre à la remise, l'app ne prélève que la
 * COMMISSION de la modification, AU VOYAGEUR, au moment où il l'accepte (il est dans l'app, une 3DS est
 * possible). Même choix que l'acceptation espèces d'un colis et que le supplément ({@code CommissionSource}) :
 * <ul>
 *   <li>{@code WALLET_FIRST} (défaut) : portefeuille de la devise du colis d'abord, complément sur la devise
 *       active ou {@code fundingCurrency}, tout ou rien, dans la transaction de l'acceptation. Solde court :
 *       422 {@code amendment-commission-unfunded} avec les portefeuilles qui couvriraient
 *       ({@code alternatives}), la modification reste {@code PROPOSED} et rien n'est débité ;</li>
 *   <li>{@code CARD} : carte commission du voyageur, débitée APRÈS le commit de l'acceptation (jamais d'appel
 *       Stripe sous le verrou du colis). La modification attend en {@code COMMISSION_PENDING}, pendant
 *       {@code commissionConfirmWindow} à compter de {@code respondedAt} ; une 3DS renvoie
 *       {@code commissionClientSecret}, puis {@link #confirmCommission} (ou le webhook) la rend acquise.
 *       Carte refusée ou 3DS échouée : la modification repasse en {@code PROPOSED} (commission {@code FAILED},
 *       kilos gardés) et le voyageur peut réessayer d'accepter jusqu'à {@code expiresAt}, avec le portefeuille
 *       ou une autre carte (nouvelle PaymentIntent, clé d'idempotence neuve, après relecture de la précédente) ;
 *       seule la PaymentIntent de l'essai courant peut l'accepter, toute autre qui aboutit est remboursée ; échéance de
 *       {@code commissionConfirmWindow} sans réponse : annulée ({@code COMMISSION_NOT_CONFIRMED}).</li>
 * </ul>
 * La commission est rendue si la modification n'aboutit pas ({@link BidAmendmentCashRefunds}). Ni bon de
 * parrainage, ni code promo, ni {@code bids.commission_*} : la commission principale du colis reste intacte. Une
 * commission nulle (taux nul) ne prélève rien : la modification est acquise tout de suite.
 */
@Component
public class BidAmendmentCashRail implements BidAmendmentRailHandler {

    private static final Logger log = LoggerFactory.getLogger(BidAmendmentCashRail.class);

    /** Métadonnée de routage de la PaymentIntent de commission ({@code CashCommissionWebhookHandler} attend {@code cash_bid}). */
    public static final String COMMISSION_PURPOSE = "bid_amendment_commission";

    static final String SCOPE = "BID_AMENDMENT_COMMISSION";

    private final BidAmendmentService service;
    private final WalletCommissionCollector collector;
    private final ActiveCurrencyResolver activeCurrencyResolver;
    private final StripeSupplementGateway gateway;
    private final BidAmendmentCashLedger ledger;
    private final BidAmendmentCashRefunds refunds;
    private final BidAmendmentRepository amendments;
    private final BidAmendmentItemRepository amendmentItems;
    private final UserRepository users;
    private final BidAmendmentWindow window;
    private final AdminAlertEscalator alerts;

    public BidAmendmentCashRail(BidAmendmentService service, WalletCommissionCollector collector,
                                ActiveCurrencyResolver activeCurrencyResolver, StripeSupplementGateway gateway,
                                BidAmendmentCashLedger ledger, BidAmendmentCashRefunds refunds,
                                BidAmendmentRepository amendments, BidAmendmentItemRepository amendmentItems,
                                UserRepository users, BidAmendmentWindow window,
                                AdminAlertEscalator alerts) {
        this.service = service;
        this.collector = collector;
        this.activeCurrencyResolver = activeCurrencyResolver;
        this.gateway = gateway;
        this.ledger = ledger;
        this.refunds = refunds;
        this.amendments = amendments;
        this.amendmentItems = amendmentItems;
        this.users = users;
        this.window = window;
        this.alerts = alerts;
    }

    @Override
    public BidSupplementRail rail() {
        return BidSupplementRail.CASH;
    }

    /**
     * Acceptation par le voyageur (sous les verrous du colis, du trajet et de la modification). Aucun appel
     * Stripe : la carte est débitée par {@link #afterAcceptCommit}.
     */
    @Override
    public AmendmentAcceptResponse accept(BidAmendmentEntity a, UserEntity traveler, AmendmentAcceptRequest request) {
        if (a.getCommissionAmount() == null || a.getCommissionAmount().signum() == 0) {
            forgetFailedAttempt(a);
            service.markAccepted(a);
            return response(a, null);
        }
        CommissionSource source = request == null || request.commissionSource() == null
                ? CommissionSource.WALLET_FIRST : request.commissionSource();
        if (source == CommissionSource.CARD) {
            if (traveler.getCommissionPaymentMethodId() == null) {
                throw new CommissionMethodMissingException();
            }
            // respondedAt = début de la fenêtre commissionConfirmWindow lue par l'expiration, et numéro de l'essai
            // (en millisecondes : la valeur relue en base, qui porte l'essai, est identique à celle d'ici).
            // Après un essai échoué, commission_status reste FAILED et la PaymentIntent précédente reste enregistrée :
            // chargeCard la relit avant d'en créer une neuve.
            a.setStatus(BidAmendmentStatus.COMMISSION_PENDING);
            a.setRespondedAt(window.now().truncatedTo(ChronoUnit.MILLIS));
            a.setCommissionChargedVia(CommissionChargedVia.CARD);
            amendments.save(a);
            ledger.audit(a, "BID_AMENDMENT_COMMISSION_PENDING", traveler.getId(), Map.of());
            return response(a, null);
        }
        chargeWallet(a, request == null ? null : request.fundingCurrency());
        service.markAccepted(a);
        return response(a, null);
    }

    /**
     * Acceptée sans passer par la carte après un essai par carte échoué : la PaymentIntent de cet essai n'est plus
     * celle de la modification (si elle aboutit tard, elle sera remboursée comme toute PaymentIntent hors essai).
     */
    private static void forgetFailedAttempt(BidAmendmentEntity a) {
        if (a.getCommissionStatus() == CommissionStatus.FAILED) {
            a.setCommissionStatus(null);
            a.setCommissionPaymentIntentId(null);
            a.setCommissionChargedVia(null);
        }
    }

    /**
     * Solde : portefeuille du colis puis complément, sous verrou ({@code plan} en {@code MANDATORY}), dans la
     * transaction de l'acceptation. Tout ou rien : un solde court lève le 422 et annule la transaction, la
     * modification reste {@code PROPOSED}.
     */
    private void chargeWallet(BidAmendmentEntity a, String fundingCurrency) {
        String bidCurrency = WalletCommissionCollector.normalize(a.getCurrency());
        String complement = WalletCommissionCollector.complementCurrency(activeCurrencyResolver,
                a.getTravelerId(), fundingCurrency);
        BigDecimal commission = a.getCommissionAmount();
        CommissionSplit split = collector.plan(a.getTravelerId(), bidCurrency, complement,
                WalletCommissionCollector.preferredOrNull(fundingCurrency), commission);
        if (!split.covered()) {
            throw unfunded(a.getTravelerId(), split, bidCurrency, complement, commission);
        }
        collector.executeForAmendment(split, a.getTravelerId(), a.getBidId(), a.getId());
        a.setCommissionPaymentIntentId(null);
        a.setCommissionChargedVia(CommissionChargedVia.WALLET);
        a.setCommissionStatus(CommissionStatus.CHARGED);
        amendments.save(a);
        Map<String, Object> extra = new LinkedHashMap<>();
        if (split.remainingBid().signum() > 0) {
            extra.put("activeCurrency", split.activeCurrency());
        }
        extra.put("lines", WalletCommissionCollector.auditLines(split));
        ledger.audit(a, "BID_AMENDMENT_COMMISSION_CHARGED_WALLET", a.getTravelerId(), extra);
    }

    /**
     * 422 {@code amendment-commission-unfunded} : mêmes propriétés que {@code supplement-commission-unfunded} et
     * que l'{@code INSUFFICIENT_WALLET} de l'acceptation espèces ({@link CommissionUnfundedProblem}).
     */
    private YadonyBusinessException unfunded(UUID travelerId, CommissionSplit split, String bidCurrency,
                                             String complement, BigDecimal commission) {
        boolean hasCard = users.findById(travelerId).map(u -> u.getCommissionPaymentMethodId() != null).orElse(false);
        return CommissionUnfundedProblem.of("amendment-commission-unfunded", "Amendment Commission Unfunded",
                "Votre solde ne couvre pas la commission de cette modification : rechargez-le ou payez par carte",
                split, bidCurrency, complement, commission, hasCard,
                collector.alternatives(travelerId, bidCurrency, complement, commission));
    }

    /** Après le commit de l'acceptation : la carte commission est débitée (hors verrou du colis). */
    @Override
    public AmendmentAcceptResponse afterAcceptCommit(AmendmentAcceptResponse accepted) {
        if (!BidAmendmentStatus.COMMISSION_PENDING.name().equals(accepted.amendment().status())) {
            return accepted;
        }
        return chargeCard(accepted.amendment().id());
    }

    /**
     * Débit de la carte commission du voyageur, présent dans l'app : confirmation immédiate SANS
     * {@code off_session}, pour qu'une 3DS revienne en {@code requires_action} (et non en refus
     * {@code authentication_required}). Une PaymentIntent par essai : clé {@code amendment_commission_<id>_<essai>}
     * ({@link #commissionKey}) et métadonnée {@code commission_attempt}. Après un essai échoué, la PaymentIntent
     * précédente est relue d'abord ({@link #settlePreviousAttempt}). Après une erreur Stripe d'issue inconnue
     * (502/503), {@link #confirmCommission} relance cet appel avec la même clé : Stripe rend la même
     * PaymentIntent, jamais un second débit.
     */
    AmendmentAcceptResponse chargeCard(UUID amendmentId) {
        BidAmendmentEntity a = ledger.find(amendmentId).orElseThrow(BidAmendmentCashRail::notFound);
        boolean firstAttempt = a.getCommissionStatus() == null && a.getCommissionPaymentIntentId() == null;
        if (a.getStatus() != BidAmendmentStatus.COMMISSION_PENDING
                || a.getCommissionChargedVia() != CommissionChargedVia.CARD
                || !(firstAttempt || a.getCommissionStatus() == CommissionStatus.FAILED)) {
            return response(a, null);
        }
        String attempt = BidAmendmentCashLedger.attemptOf(a);
        UserEntity traveler = users.findById(a.getTravelerId()).orElseThrow();
        if (traveler.getCommissionPaymentMethodId() == null) {
            // Carte détachée entre l'acceptation et le débit.
            ledger.commissionFailed(amendmentId, null, attempt, "no-commission-card");
            throw new CommissionMethodMissingException();
        }
        if (!firstAttempt) {
            settlePreviousAttempt(a);
        }
        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(a.getCurrency());
        CurrencyAmount amount = CurrencyAmount.of(a.getCommissionAmount(), currency);
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amount.minor())
                .setCurrency(currency.code())
                .setCustomer(traveler.getStripeCustomerId())
                .setPaymentMethod(traveler.getCommissionPaymentMethodId())
                .addPaymentMethodType("card")
                .setConfirm(true)
                .setDescription("Commission modification " + a.getId())
                // Ni bid_id ni payment_id : aucun traitement du colis ne doit la prendre pour la sienne.
                .putMetadata("bid_amendment_id", a.getId().toString())
                .putMetadata("commission_purpose", COMMISSION_PURPOSE)
                .putMetadata("commission_attempt", attempt)
                .putMetadata("scope", SCOPE)
                .putMetadata("amendment_bid_id", a.getBidId().toString())
                .build();
        PaymentIntentView pi;
        try {
            pi = gateway.createPaymentIntent(params, commissionKey(a));
        } catch (CardException e) {
            ledger.commissionFailed(amendmentId, null, attempt, "card-declined:" + e.getCode());
            throw cardDeclined(e.getCode());
        } catch (StripeException e) {
            // Issue inconnue : la modification reste en attente ; le webhook (ou le filet à 30 min) tranche.
            log.error("Commission carte de la modification {} : erreur Stripe", amendmentId, e);
            if (StripeUnavailable.isNetworkFailure(e)) {
                throw StripeUnavailable.exception();
            }
            throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "amendment-commission-charge-failed",
                    "Commission Charge Failed", "Le débit de la commission n'a pas pu aboutir. Veuillez réessayer.");
        }
        return applyPaymentIntent(amendmentId, pi, "create", attempt);
    }

    /**
     * Avant un nouvel essai par carte : la PaymentIntent de l'essai précédent est relue chez Stripe, jamais
     * supposée morte. Aboutie : elle sera remboursée (l'essai continue). {@code processing} : l'argent peut encore
     * partir, le nouvel essai est refusé (409 {@code amendment-commission-in-progress}, modification remise en
     * {@code PROPOSED}). Sinon annulée ; si l'annulation est refusée elle est relue, et une alerte part si elle
     * reste vivante.
     */
    private void settlePreviousAttempt(BidAmendmentEntity a) {
        String previous = a.getCommissionPaymentIntentId();
        if (previous == null) {
            return;
        }
        PaymentIntentView pi = retrieve(previous);
        if (pi.canceled()) {
            return;
        }
        if (!pi.succeeded() && !"processing".equals(pi.status())) {
            try {
                gateway.cancelPaymentIntent(previous);
                return;
            } catch (StripeException refused) {
                pi = retrieveOrNull(previous);
                if (pi == null || !(pi.succeeded() || pi.canceled() || "processing".equals(pi.status()))) {
                    log.error("Commission {} de la modification {} : annulation refusée, PaymentIntent encore vivante",
                            previous, a.getId(), refused);
                    alertUncanceled(a.getId(), previous, refused.getMessage());
                    return;
                }
            }
        }
        if (pi.succeeded()) {
            ledger.reportStale(a.getId(), previous, "retry-check");
        } else if ("processing".equals(pi.status())) {
            ledger.abortRetry(a.getId());
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "amendment-commission-in-progress",
                    "Amendment Commission In Progress",
                    "Un prélèvement de votre commission est encore en cours de traitement : réessayez dans quelques minutes");
        }
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

    private PaymentIntentView retrieveOrNull(String paymentIntentId) {
        try {
            return gateway.retrievePaymentIntent(paymentIntentId);
        } catch (StripeException e) {
            return null;
        }
    }

    /**
     * Après la 3DS ({@code POST …/commission/confirm}, route en B7) : relit la PaymentIntent de commission et
     * enregistre son issue. Idempotent ; vue du voyageur. Aucun verrou ici : chaque transition du registre prend
     * d'abord celui du colis.
     */
    public BidAmendmentResponse confirmCommission(UUID bidId, UUID amendmentId, String firebaseUid) {
        UserEntity traveler = users.findByFirebaseUid(firebaseUid).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.UNAUTHORIZED, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        BidAmendmentEntity a = amendments.findById(amendmentId).filter(found -> found.getBidId().equals(bidId))
                .orElseThrow(BidAmendmentCashRail::notFound);
        if (!traveler.getId().equals(a.getTravelerId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas partie à cette modification");
        }
        if (a.getRail() != BidSupplementRail.CASH) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "amendment-rail-unavailable",
                    "Amendment Rail Unavailable", "Cette modification n'a pas de commission à confirmer");
        }
        if (a.getStatus() != BidAmendmentStatus.COMMISSION_PENDING) {
            return response(a, null).amendment();
        }
        if (a.getCommissionPaymentIntentId() == null || BidAmendmentCashLedger.retryInProgress(a)) {
            // Débit de la carte resté sans réponse (502/503) ou nouvel essai dont la PaymentIntent n'existe pas
            // encore : on (re)lance le débit, même clé.
            return a.getCommissionChargedVia() == CommissionChargedVia.CARD
                    && (a.getCommissionStatus() == null || a.getCommissionStatus() == CommissionStatus.FAILED)
                    ? chargeCard(amendmentId).amendment() : response(a, null).amendment();
        }
        PaymentIntentView pi = retrieve(a.getCommissionPaymentIntentId());
        return applyPaymentIntent(amendmentId, pi, "confirm", BidAmendmentCashLedger.attemptOf(a)).amendment();
    }

    /** Issue d'une PaymentIntent de commission (débit, confirmation). */
    private AmendmentAcceptResponse applyPaymentIntent(UUID amendmentId, PaymentIntentView pi, String source,
                                                       String attempt) {
        String status = pi.status() == null ? "" : pi.status();
        switch (status) {
            case "succeeded" -> {
                ledger.commissionCharged(amendmentId, pi.id(), attempt, source);
                return response(reload(amendmentId), null);
            }
            case "requires_action", "requires_confirmation", "processing" -> {
                BidAmendmentEntity a = ledger.commissionRequiresAction(amendmentId, pi.id(), attempt);
                if (a.getStatus() != BidAmendmentStatus.COMMISSION_PENDING) {
                    // Modification close pendant le débit (annulation, colis terminé) : la PaymentIntent ne doit
                    // plus pouvoir aboutir. Annulée tout de suite (si elle aboutit, elle sera remboursée). Déjà
                    // acquise (le webhook a devancé cette réponse) : rien à défaire.
                    if (!a.getStatus().isSecured()) {
                        cancelQuietly(amendmentId, pi.id(), attempt);
                        refunds.settle(amendmentId);
                    }
                    return response(reload(amendmentId), null);
                }
                // processing : rare pour une carte, le webhook tranchera ; pas de 3DS à présenter.
                return response(a, "processing".equals(status) ? null : pi.clientSecret());
            }
            default -> {
                // requires_payment_method (refus ou 3DS échouée), canceled : rien n'a été prélevé. La modification
                // repasse en PROPOSED ; la PaymentIntent est annulée pour qu'un vieux client secret ne puisse
                // plus débiter.
                ledger.commissionFailed(amendmentId, pi.id(), attempt, "status-" + status);
                if (!pi.canceled()) {
                    cancelQuietly(amendmentId, pi.id(), attempt);
                }
                throw cardDeclined(null);
            }
        }
    }

    /** Webhook d'une PaymentIntent de commission (routé par {@link BidAmendmentStripeWebhookRouter}). */
    public void onCommissionWebhook(String type, UUID amendmentId, String paymentIntentId, String attempt,
                                    String failureCode) {
        switch (type) {
            case "payment_intent.succeeded" ->
                    ledger.commissionCharged(amendmentId, paymentIntentId, attempt, "webhook");
            case "payment_intent.payment_failed" -> {
                // 3DS échouée : la modification repasse en PROPOSED ; la PaymentIntent est annulée pour qu'une
                // nouvelle tentative depuis un vieux client secret ne puisse plus débiter.
                if (ledger.commissionFailed(amendmentId, paymentIntentId, attempt, "payment_failed:" + failureCode)) {
                    cancelQuietly(amendmentId, paymentIntentId, attempt);
                }
            }
            case "payment_intent.canceled" ->
                    ledger.commissionFailed(amendmentId, paymentIntentId, attempt, "canceled");
            default -> log.debug("Webhook {} de la commission de la modification {} : rien à faire", type,
                    amendmentId);
        }
    }

    /**
     * Annule la PaymentIntent d'un essai terminé. Stripe refuse d'annuler une PaymentIntent {@code succeeded} ou
     * {@code processing} : le refus n'est jamais avalé. Elle est relue ; aboutie, elle est rendue (commission hors
     * essai) ; encore vivante ou illisible, une alerte admin part.
     */
    private void cancelQuietly(UUID amendmentId, String paymentIntentId, String attempt) {
        if (paymentIntentId == null) {
            return;
        }
        try {
            gateway.cancelPaymentIntent(paymentIntentId);
        } catch (StripeException refused) {
            PaymentIntentView pi = retrieveOrNull(paymentIntentId);
            if (pi != null && pi.canceled()) {
                return;
            }
            if (pi != null && pi.succeeded()) {
                ledger.commissionCharged(amendmentId, paymentIntentId, attempt, "cancel-refused");
                return;
            }
            log.error("Commission {} de la modification {} : annulation refusée ({})", paymentIntentId, amendmentId,
                    refused.getMessage());
            alertUncanceled(amendmentId, paymentIntentId, refused.getMessage());
        }
    }

    private void alertUncanceled(UUID amendmentId, String paymentIntentId, String error) {
        alerts.raiseOnce("AMEND_COMM_PI_UNCANCEL_" + paymentIntentId,
                "PaymentIntent de commission " + paymentIntentId + " de la modification " + amendmentId
                        + " d'un essai terminé impossible à annuler : elle peut encore aboutir (" + error + ")",
                Map.of("amendmentId", amendmentId.toString(), "paymentIntentId", paymentIntentId,
                        "error", String.valueOf(error)));
    }

    /**
     * Clé d'idempotence du débit : une par essai ({@link BidAmendmentCashLedger#attemptOf}, {@code respondedAt}).
     * Un nouvel essai a une clé neuve (sinon Stripe renverrait la PaymentIntent échouée du précédent), un rejeu
     * du même essai (502, confirmation) retrouve la même PaymentIntent.
     */
    static String commissionKey(BidAmendmentEntity a) {
        String attempt = BidAmendmentCashLedger.attemptOf(a);
        return "amendment_commission_" + a.getId() + "_" + (attempt == null ? "0" : attempt);
    }

    private BidAmendmentEntity reload(UUID amendmentId) {
        return ledger.find(amendmentId).orElseThrow(BidAmendmentCashRail::notFound);
    }

    /** Vue voyageur (net et commission visibles), avec le client secret de la 3DS le cas échéant. */
    private AmendmentAcceptResponse response(BidAmendmentEntity a, String commissionClientSecret) {
        BidAmendmentResponse view = BidAmendmentViews.of(a, amendmentItems.findByAmendmentId(a.getId()), false, window);
        return new AmendmentAcceptResponse(
                commissionClientSecret == null ? view : view.withCommissionClientSecret(commissionClientSecret),
                commissionClientSecret);
    }

    private static YadonyBusinessException cardDeclined(String declineCode) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (declineCode != null) {
            props.put("declineCode", declineCode);
        }
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "amendment-commission-card-declined",
                "Commission Card Declined",
                "Votre carte commission a été refusée : rien n'a été prélevé, vous pouvez réessayer d'accepter", props);
    }

    private static YadonyBusinessException notFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "amendment-not-found", "Amendment Not Found",
                "Modification introuvable");
    }
}
