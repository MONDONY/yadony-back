package com.yadony.api.payments.amendment;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.InvalidRequestException;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptRequest;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptResponse;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionSource;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.WalletCommissionCollector;
import com.yadony.api.payments.cash.exception.CommissionMethodMissingException;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.supplement.StripeSupplementGateway;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Branches du rail espèces des modifications que l'IT ne parcourt pas (erreurs Stripe, rejeux, états inattendus). */
@ExtendWith(MockitoExtension.class)
@DisplayName("BidAmendmentCashRail : branches et erreurs")
class BidAmendmentCashRailTest {

    @Mock BidAmendmentService service;
    @Mock WalletCommissionCollector collector;
    @Mock ActiveCurrencyResolver activeCurrencyResolver;
    @Mock StripeSupplementGateway gateway;
    @Mock BidAmendmentCashLedger ledger;
    @Mock BidAmendmentCashRefunds refunds;
    @Mock BidAmendmentRepository amendments;
    @Mock BidAmendmentItemRepository amendmentItems;
    @Mock UserRepository users;
    @Mock AdminAlertEscalator alerts;

    private BidAmendmentCashRail rail;
    private BidAmendmentEntity a;
    private UserEntity traveler;

    @BeforeEach
    void setUp() {
        rail = new BidAmendmentCashRail(service, collector, activeCurrencyResolver, gateway, ledger, refunds,
                amendments, amendmentItems, users, new BidAmendmentWindow(new BidAmendmentProperties()), alerts);
        traveler = new UserEntity();
        BidAmendmentTestSupport.setId(traveler, UUID.randomUUID());
        traveler.setFirebaseUid("uid-traveler");
        traveler.setCommissionPaymentMethodId("pm_card");
        traveler.setStripeCustomerId("cus_1");
        a = new BidAmendmentEntity();
        BidAmendmentTestSupport.setId(a, UUID.randomUUID());
        a.setBidId(UUID.randomUUID());
        a.setTravelerId(traveler.getId());
        a.setRail(BidSupplementRail.CASH);
        a.setStatus(BidAmendmentStatus.PROPOSED);
        a.setCurrency("EUR");
        a.setNetAmount(new BigDecimal("30.00"));
        a.setCommissionAmount(new BigDecimal("3.60"));
        a.setGrossAmount(new BigDecimal("33.60"));
        a.setAddedWeightKg(new BigDecimal("3"));
        a.setExpiresAt(java.time.LocalDateTime.now().plusHours(5));
    }

    private static PaymentIntentView pi(String status) {
        return new PaymentIntentView("pi_c", status, 360L, "eur", "pi_c_secret", null, List.of("card"));
    }

    private void commissionPendingByCard() {
        a.setStatus(BidAmendmentStatus.COMMISSION_PENDING);
        a.setCommissionChargedVia(CommissionChargedVia.CARD);
    }

    // ── accept ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("commission nulle : acquise tout de suite, ni portefeuille ni carte")
    void accept_zeroCommission_marksAcceptedWithoutCharge() {
        a.setCommissionAmount(BigDecimal.ZERO);

        AmendmentAcceptResponse r = rail.accept(a, traveler, new AmendmentAcceptRequest(CommissionSource.CARD, null));

        verify(service).markAccepted(a);
        verifyNoInteractions(collector, gateway);
        assertThat(r.commissionClientSecret()).isNull();
    }

    @Test
    @DisplayName("corps absent : portefeuille par défaut, devise de complément invalide refusée en 422")
    void accept_nullRequestDefaultsToWallet_andInvalidFundingCurrencyRefused() {
        assertThat(catchThrowable(() -> rail.accept(a, traveler, new AmendmentAcceptRequest(null, "ZZZ"))))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("funding-currency-invalid"));

        when(activeCurrencyResolver.resolve(traveler.getId())).thenReturn("EUR");
        when(collector.plan(traveler.getId(), "EUR", "EUR", a.getCommissionAmount())).thenReturn(
                new com.yadony.api.payments.cash.CommissionSplit("EUR", a.getCommissionAmount(),
                        a.getCommissionAmount(), BigDecimal.ZERO, "EUR", BigDecimal.ZERO, null, BigDecimal.TEN,
                        BigDecimal.TEN, true, a.getCommissionAmount()));

        rail.accept(a, traveler, null);

        verify(collector).executeForAmendment(any(), eq(traveler.getId()), eq(a.getBidId()), eq(a.getId()));
        verify(service).markAccepted(a);
        assertThat(a.getCommissionChargedVia()).isEqualTo(CommissionChargedVia.WALLET);
        assertThat(a.getCommissionStatus()).isEqualTo(CommissionStatus.CHARGED);
    }

    @Test
    @DisplayName("carte demandée sans carte commission enregistrée : refus, rien ne change")
    void accept_cardWithoutCard_refused() {
        traveler.setCommissionPaymentMethodId(null);

        assertThat(catchThrowable(() -> rail.accept(a, traveler, new AmendmentAcceptRequest(CommissionSource.CARD,
                null)))).isInstanceOf(CommissionMethodMissingException.class);

        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.PROPOSED);
        verify(service, never()).markAccepted(any());
    }

    @Test
    @DisplayName("nouvel essai par carte après un refus : la PaymentIntent précédente et FAILED restent, clé neuve")
    void accept_cardRetry_keepsPreviousPaymentIntentForVerification() {
        a.setCommissionStatus(CommissionStatus.FAILED);
        a.setCommissionPaymentIntentId("pi_old");
        a.setCommissionChargedVia(CommissionChargedVia.CARD);

        rail.accept(a, traveler, new AmendmentAcceptRequest(CommissionSource.CARD, null));

        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.COMMISSION_PENDING);
        assertThat(a.getCommissionStatus()).isEqualTo(CommissionStatus.FAILED);
        assertThat(a.getCommissionPaymentIntentId()).isEqualTo("pi_old");
        assertThat(a.getRespondedAt()).isNotNull();
        String first = BidAmendmentCashRail.commissionKey(a);
        a.setRespondedAt(a.getRespondedAt().plusSeconds(1));
        assertThat(BidAmendmentCashRail.commissionKey(a)).as("une clé par essai").isNotEqualTo(first)
                .startsWith("amendment_commission_" + a.getId() + "_");
        a.setRespondedAt(null);
        assertThat(BidAmendmentCashRail.commissionKey(a)).endsWith("_0");
    }

    @Test
    @DisplayName("essai au solde ou commission nulle après un essai par carte échoué : la PaymentIntent précédente est oubliée")
    void accept_walletOrZeroAfterFailedCardAttempt_forgetsPreviousPaymentIntent() {
        a.setCommissionStatus(CommissionStatus.FAILED);
        a.setCommissionPaymentIntentId("pi_old");
        a.setCommissionChargedVia(CommissionChargedVia.CARD);
        a.setCommissionAmount(BigDecimal.ZERO);

        rail.accept(a, traveler, new AmendmentAcceptRequest(null, null));

        assertThat(a.getCommissionStatus()).isNull();
        assertThat(a.getCommissionPaymentIntentId()).isNull();
        assertThat(a.getCommissionChargedVia()).isNull();

        a.setCommissionAmount(new BigDecimal("3.60"));
        a.setCommissionPaymentIntentId("pi_old2");
        when(activeCurrencyResolver.resolve(traveler.getId())).thenReturn("EUR");
        when(collector.plan(traveler.getId(), "EUR", "EUR", a.getCommissionAmount())).thenReturn(
                new com.yadony.api.payments.cash.CommissionSplit("EUR", a.getCommissionAmount(),
                        a.getCommissionAmount(), BigDecimal.ZERO, "EUR", BigDecimal.ZERO, null, BigDecimal.TEN,
                        BigDecimal.TEN, true, a.getCommissionAmount()));

        rail.accept(a, traveler, new AmendmentAcceptRequest(null, null));

        assertThat(a.getCommissionPaymentIntentId()).as("la PaymentIntent d'un essai échoué n'est pas celle du solde")
                .isNull();
        assertThat(a.getCommissionChargedVia()).isEqualTo(CommissionChargedVia.WALLET);
    }

    @Test
    @DisplayName("afterAcceptCommit : une modification déjà acquise n'appelle pas Stripe")
    void afterAcceptCommit_notPending_returnsAsIs() {
        AmendmentAcceptResponse accepted = new AmendmentAcceptResponse(
                BidAmendmentViews.of(a, List.of(), false, new BidAmendmentWindow(new BidAmendmentProperties())), null);

        assertThat(rail.afterAcceptCommit(accepted)).isSameAs(accepted);
        verifyNoInteractions(gateway);
    }

    // ── chargeCard ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("chargeCard : déjà débitée ou plus en attente, aucun second appel Stripe")
    void chargeCard_notChargeable_noStripeCall() {
        when(ledger.find(a.getId())).thenReturn(Optional.of(a));
        // PROPOSED : pas en attente de commission.
        rail.chargeCard(a.getId());
        // COMMISSION_PENDING mais PaymentIntent déjà posée.
        commissionPendingByCard();
        a.setCommissionPaymentIntentId("pi_c");
        rail.chargeCard(a.getId());

        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("chargeCard : carte détachée entre l'acceptation et le débit, la modification est close")
    void chargeCard_cardDetached_failsAmendment() {
        commissionPendingByCard();
        traveler.setCommissionPaymentMethodId(null);
        when(ledger.find(a.getId())).thenReturn(Optional.of(a));
        when(users.findById(traveler.getId())).thenReturn(Optional.of(traveler));

        assertThat(catchThrowable(() -> rail.chargeCard(a.getId()))).isInstanceOf(CommissionMethodMissingException.class);

        verify(ledger).commissionFailed(a.getId(), null, null, "no-commission-card");
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("chargeCard : Stripe injoignable → 502 stripe-unavailable ; refus Stripe → 502 charge-failed")
    void chargeCard_stripeErrors() throws Exception {
        commissionPendingByCard();
        when(ledger.find(a.getId())).thenReturn(Optional.of(a));
        when(users.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(gateway.createPaymentIntent(any(), anyString())).thenThrow(new ApiConnectionException("timeout"))
                .thenThrow(new InvalidRequestException("bad", "req", "code", "param", 400, null));

        assertThat(catchThrowable(() -> rail.chargeCard(a.getId()))).isInstanceOfSatisfying(
                YadonyBusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo("stripe-unavailable"));
        assertThat(catchThrowable(() -> rail.chargeCard(a.getId()))).isInstanceOfSatisfying(
                YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("amendment-commission-charge-failed"));
        verify(ledger, never()).commissionFailed(any(), any(), any(), any());
    }

    @Test
    @DisplayName("débit réussi : commission enregistrée, modification acquise")
    void chargeCard_succeeded_recordsCommission() throws Exception {
        commissionPendingByCard();
        when(ledger.find(a.getId())).thenReturn(Optional.of(a));
        when(users.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(gateway.createPaymentIntent(any(), anyString()))
                .thenReturn(pi("succeeded"));

        AmendmentAcceptResponse r = rail.chargeCard(a.getId());

        verify(ledger).commissionCharged(a.getId(), "pi_c", null, "create");
        assertThat(r.commissionClientSecret()).isNull();
    }

    @Test
    @DisplayName("PaymentIntent en processing : pas de 3DS à présenter ; refus ou annulation : carte refusée")
    void chargeCard_processingThenDeclined() throws Exception {
        commissionPendingByCard();
        when(ledger.find(a.getId())).thenReturn(Optional.of(a));
        when(users.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(pi("processing"))
                .thenReturn(pi("requires_payment_method"));
        when(ledger.commissionRequiresAction(a.getId(), "pi_c", null)).thenReturn(a);

        assertThat(rail.chargeCard(a.getId()).commissionClientSecret()).isNull();
        assertThat(catchThrowable(() -> rail.chargeCard(a.getId()))).isInstanceOfSatisfying(
                YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("amendment-commission-card-declined"));
        verify(ledger).commissionFailed(a.getId(), "pi_c", null, "status-requires_payment_method");
        verify(gateway).cancelPaymentIntent("pi_c");
    }

    @Test
    @DisplayName("modification close pendant le débit : PaymentIntent défaite ; déjà acquise : rien à défaire")
    void chargeCard_closedDuringCharge_settles_acceptedLeftAlone() throws Exception {
        commissionPendingByCard();
        when(ledger.find(a.getId())).thenReturn(Optional.of(a));
        when(users.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(pi("requires_action"));
        BidAmendmentEntity cancelled = new BidAmendmentEntity();
        BidAmendmentTestSupport.setId(cancelled, a.getId());
        cancelled.setRail(BidSupplementRail.CASH);
        cancelled.setCurrency("EUR");
        cancelled.setNetAmount(a.getNetAmount());
        cancelled.setGrossAmount(a.getGrossAmount());
        cancelled.setCommissionAmount(a.getCommissionAmount());
        cancelled.setAddedWeightKg(a.getAddedWeightKg());
        cancelled.setExpiresAt(a.getExpiresAt());
        cancelled.setStatus(BidAmendmentStatus.CANCELLED);
        when(ledger.commissionRequiresAction(a.getId(), "pi_c", null)).thenReturn(cancelled);

        rail.chargeCard(a.getId());
        verify(refunds).settle(a.getId());

        cancelled.setStatus(BidAmendmentStatus.ACCEPTED);
        rail.chargeCard(a.getId());
        verify(refunds, org.mockito.Mockito.times(1)).settle(a.getId());
    }

    // ── confirmCommission ────────────────────────────────────────────────────

    private void asTraveler() {
        when(users.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
    }

    @Test
    @DisplayName("confirm : utilisateur inconnu 401, autre colis 404, autre voyageur 403, autre rail 422")
    void confirm_guards() {
        when(users.findByFirebaseUid("ghost")).thenReturn(Optional.empty());
        assertThat(catchThrowable(() -> rail.confirmCommission(a.getBidId(), a.getId(), "ghost")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(401));

        asTraveler();
        when(amendments.findById(a.getId())).thenReturn(Optional.of(a));
        assertThat(catchThrowable(() -> rail.confirmCommission(UUID.randomUUID(), a.getId(), "uid-traveler")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-not-found"));

        a.setTravelerId(UUID.randomUUID());
        assertThat(catchThrowable(() -> rail.confirmCommission(a.getBidId(), a.getId(), "uid-traveler")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(403));

        a.setTravelerId(traveler.getId());
        a.setRail(BidSupplementRail.STRIPE);
        assertThat(catchThrowable(() -> rail.confirmCommission(a.getBidId(), a.getId(), "uid-traveler")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-rail-unavailable"));
    }

    @Test
    @DisplayName("confirm : modification qui n'attend plus de commission, la vue telle quelle")
    void confirm_notPending_returnsView() {
        asTraveler();
        when(amendments.findById(a.getId())).thenReturn(Optional.of(a));

        assertThat(rail.confirmCommission(a.getBidId(), a.getId(), "uid-traveler").status()).isEqualTo("PROPOSED");
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("confirm : débit resté sans réponse, relancé ; sinon vue telle quelle")
    void confirm_noPaymentIntentYet_retriesCharge() throws Exception {
        asTraveler();
        commissionPendingByCard();
        when(amendments.findById(a.getId())).thenReturn(Optional.of(a));
        when(ledger.find(a.getId())).thenReturn(Optional.of(a));
        when(users.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(pi("requires_action"));
        when(ledger.commissionRequiresAction(a.getId(), "pi_c", null)).thenReturn(a);

        assertThat(rail.confirmCommission(a.getBidId(), a.getId(), "uid-traveler").commissionClientSecret())
                .isEqualTo("pi_c_secret");

        // Commission déjà en 3DS (statut posé) sans PaymentIntent connue : on ne relance pas.
        a.setCommissionStatus(CommissionStatus.REQUIRES_3DS);
        rail.confirmCommission(a.getBidId(), a.getId(), "uid-traveler");
        verify(gateway, org.mockito.Mockito.times(1)).createPaymentIntent(any(), anyString());
    }

    @Test
    @DisplayName("confirm : relecture de la PaymentIntent en échec réseau 502, autre erreur 502 stripe-error")
    void confirm_retrieveFailures() throws Exception {
        asTraveler();
        commissionPendingByCard();
        a.setCommissionPaymentIntentId("pi_c");
        when(amendments.findById(a.getId())).thenReturn(Optional.of(a));
        when(gateway.retrievePaymentIntent("pi_c")).thenThrow(new ApiConnectionException("timeout"))
                .thenThrow(new InvalidRequestException("bad", "req", "code", "param", 400, null));

        assertThat(catchThrowable(() -> rail.confirmCommission(a.getBidId(), a.getId(), "uid-traveler")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("stripe-unavailable"));
        assertThat(catchThrowable(() -> rail.confirmCommission(a.getBidId(), a.getId(), "uid-traveler")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("stripe-error"));
    }

    // ── Webhook ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("webhook : succès enregistré ; annulation = commission non prélevée ; autre type ignoré")
    void webhook_succeededCanceledAndOthers() {
        rail.onCommissionWebhook("payment_intent.succeeded", a.getId(), "pi_c", "7", "unknown");
        rail.onCommissionWebhook("payment_intent.canceled", a.getId(), "pi_c", "7", "unknown");
        rail.onCommissionWebhook("payment_intent.created", a.getId(), "pi_c", "7", "unknown");

        verify(ledger).commissionCharged(a.getId(), "pi_c", "7", "webhook");
        verify(ledger).commissionFailed(a.getId(), "pi_c", "7", "canceled");
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("webhook : 3DS échouée, la PaymentIntent est annulée ; annulation refusée ou sans id : sans suite")
    void webhook_paymentFailed_cancelsPaymentIntentQuietly() throws Exception {
        when(ledger.commissionFailed(a.getId(), "pi_c", "7", "payment_failed:card_declined")).thenReturn(true);
        when(ledger.commissionFailed(a.getId(), null, "7", "payment_failed:card_declined")).thenReturn(true);
        when(ledger.commissionFailed(a.getId(), "pi_old", "7", "payment_failed:card_declined")).thenReturn(false);
        when(gateway.cancelPaymentIntent("pi_c")).thenThrow(new ApiConnectionException("timeout"));

        rail.onCommissionWebhook("payment_intent.payment_failed", a.getId(), "pi_c", "7", "card_declined");
        rail.onCommissionWebhook("payment_intent.payment_failed", a.getId(), null, "7", "card_declined");
        rail.onCommissionWebhook("payment_intent.payment_failed", a.getId(), "pi_old", "7", "card_declined");

        verify(gateway).cancelPaymentIntent("pi_c");
        verify(gateway, never()).cancelPaymentIntent("pi_old");
    }

    // ── Nouvel essai : relecture de la PaymentIntent précédente ──────────────

    /** Nouvel essai par carte après un essai échoué : COMMISSION_PENDING, FAILED, PaymentIntent précédente gardée. */
    private void retryAfterFailure(String previousPi) throws Exception {
        commissionPendingByCard();
        a.setCommissionStatus(CommissionStatus.FAILED);
        a.setCommissionPaymentIntentId(previousPi);
        a.setRespondedAt(java.time.LocalDateTime.now());
        when(ledger.find(a.getId())).thenReturn(Optional.of(a));
        when(users.findById(traveler.getId())).thenReturn(Optional.of(traveler));
    }

    @Test
    @DisplayName("nouvel essai, PaymentIntent précédente aboutie : elle sera remboursée, l'essai continue avec une neuve")
    void retry_previousSucceeded_reportedStale_andNewPaymentIntentCreated() throws Exception {
        retryAfterFailure("pi_1");
        when(gateway.retrievePaymentIntent("pi_1")).thenReturn(pi("succeeded"));
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(new PaymentIntentView("pi_2",
                "requires_action", 360L, "eur", "pi_2_secret", null, List.of("card")));
        when(ledger.commissionRequiresAction(eq(a.getId()), eq("pi_2"), any())).thenReturn(a);

        AmendmentAcceptResponse r = rail.chargeCard(a.getId());

        verify(ledger).reportStale(a.getId(), "pi_1", "retry-check");
        verify(gateway, never()).cancelPaymentIntent(any());
        assertThat(r.commissionClientSecret()).isEqualTo("pi_2_secret");
    }

    @Test
    @DisplayName("nouvel essai, PaymentIntent précédente en processing : refusé en 409, modification remise en PROPOSED")
    void retry_previousProcessing_refusedWith409() throws Exception {
        retryAfterFailure("pi_1");
        when(gateway.retrievePaymentIntent("pi_1")).thenReturn(pi("processing"));

        assertThat(catchThrowable(() -> rail.chargeCard(a.getId()))).isInstanceOfSatisfying(
                YadonyBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("amendment-commission-in-progress");
                    assertThat(e.getStatus().value()).isEqualTo(409);
                });

        verify(ledger).abortRetry(a.getId());
        verify(gateway, never()).createPaymentIntent(any(), anyString());
        verify(gateway, never()).cancelPaymentIntent(any());
    }

    @Test
    @DisplayName("nouvel essai, PaymentIntent précédente encore annulable : annulée puis nouvelle PaymentIntent")
    void retry_previousCancellable_isCanceled_thenNewPaymentIntent() throws Exception {
        retryAfterFailure("pi_1");
        when(gateway.retrievePaymentIntent("pi_1")).thenReturn(pi("requires_payment_method"));
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(pi("succeeded"));

        rail.chargeCard(a.getId());

        verify(gateway).cancelPaymentIntent("pi_1");
        verify(ledger).commissionCharged(a.getId(), "pi_c", BidAmendmentCashLedger.attemptOf(a), "create");
    }

    @Test
    @DisplayName("nouvel essai, précédente déjà annulée : rien à annuler")
    void retry_previousAlreadyCanceled_nothingToCancel() throws Exception {
        retryAfterFailure("pi_1");
        when(gateway.retrievePaymentIntent("pi_1")).thenReturn(pi("canceled"));
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(pi("succeeded"));

        rail.chargeCard(a.getId());

        verify(gateway, never()).cancelPaymentIntent(any());
        verify(ledger, never()).reportStale(any(), any(), any());
    }

    @Test
    @DisplayName("nouvel essai, annulation refusée : PaymentIntent relue ; aboutie, rendue ; processing, 409")
    void retry_cancelRefused_rereadsPaymentIntent() throws Exception {
        retryAfterFailure("pi_1");
        when(gateway.retrievePaymentIntent("pi_1")).thenReturn(pi("requires_action"), pi("succeeded"),
                pi("requires_action"), pi("processing"));
        when(gateway.cancelPaymentIntent("pi_1")).thenThrow(new InvalidRequestException("cannot cancel", "req",
                "code", "param", 400, null));
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(pi("succeeded"));

        rail.chargeCard(a.getId());
        verify(ledger).reportStale(a.getId(), "pi_1", "retry-check");

        assertThat(catchThrowable(() -> rail.chargeCard(a.getId()))).isInstanceOfSatisfying(
                YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("amendment-commission-in-progress"));
        verify(ledger).abortRetry(a.getId());
        verifyNoInteractions(alerts);
    }

    @Test
    @DisplayName("nouvel essai, annulation refusée et PaymentIntent toujours vivante ou illisible : alerte admin, l'essai continue")
    void retry_cancelRefusedAndStillAlive_alerts() throws Exception {
        retryAfterFailure("pi_1");
        when(gateway.retrievePaymentIntent("pi_1")).thenReturn(pi("requires_action"))
                .thenThrow(new ApiConnectionException("timeout"));
        when(gateway.cancelPaymentIntent("pi_1")).thenThrow(new InvalidRequestException("cannot cancel", "req",
                "code", "param", 400, null));
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(pi("succeeded"));

        rail.chargeCard(a.getId());

        verify(alerts).raiseOnce(eq("AMEND_COMM_PI_UNCANCEL_pi_1"), anyString(), org.mockito.ArgumentMatchers.anyMap());
        verify(gateway).createPaymentIntent(any(), anyString());
    }

    @Test
    @DisplayName("nouvel essai, relecture de la précédente impossible : 502, rien de créé")
    void retry_previousUnreadable_noNewPaymentIntent() throws Exception {
        retryAfterFailure("pi_1");
        when(gateway.retrievePaymentIntent("pi_1")).thenThrow(new ApiConnectionException("timeout"));

        assertThat(catchThrowable(() -> rail.chargeCard(a.getId()))).isInstanceOfSatisfying(
                YadonyBusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo("stripe-unavailable"));

        verify(gateway, never()).createPaymentIntent(any(), anyString());
    }

    @Test
    @DisplayName("métadonnées de la PaymentIntent : identifiant de la modification, numéro d'essai, pas de bid_id")
    void chargeCard_metadataCarriesAttemptNumber() throws Exception {
        commissionPendingByCard();
        a.setRespondedAt(java.time.LocalDateTime.of(2026, 10, 24, 10, 0, 0));
        when(ledger.find(a.getId())).thenReturn(Optional.of(a));
        when(users.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(pi("succeeded"));

        rail.chargeCard(a.getId());

        org.mockito.ArgumentCaptor<com.stripe.param.PaymentIntentCreateParams> params =
                org.mockito.ArgumentCaptor.forClass(com.stripe.param.PaymentIntentCreateParams.class);
        verify(gateway).createPaymentIntent(params.capture(), eq(BidAmendmentCashRail.commissionKey(a)));
        assertThat(params.getValue().getMetadata())
                .containsEntry("bid_amendment_id", a.getId().toString())
                .containsEntry("commission_attempt", BidAmendmentCashLedger.attemptOf(a))
                .doesNotContainKeys("bid_id", "payment_id");
    }

    @Test
    @DisplayName("confirm pendant un nouvel essai sans PaymentIntent neuve : le débit est relancé, pas la précédente relue")
    void confirm_duringRetry_restartsCharge() throws Exception {
        asTraveler();
        retryAfterFailure("pi_1");
        when(amendments.findById(a.getId())).thenReturn(Optional.of(a));
        when(gateway.retrievePaymentIntent("pi_1")).thenReturn(pi("canceled"));
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(pi("succeeded"));

        rail.confirmCommission(a.getBidId(), a.getId(), "uid-traveler");

        verify(gateway).createPaymentIntent(any(), anyString());
    }

    // ── Annulation d'une PaymentIntent d'essai terminé ───────────────────────

    @Test
    @DisplayName("webhook d'échec : annulation refusée, PaymentIntent relue ; annulée rien, aboutie remboursée, vivante alerte")
    void webhook_paymentFailed_cancelRefused_neverSwallowed() throws Exception {
        when(ledger.commissionFailed(eq(a.getId()), anyString(), eq("7"), anyString())).thenReturn(true);
        when(gateway.cancelPaymentIntent(anyString())).thenThrow(new InvalidRequestException("cannot cancel", "req",
                "code", "param", 400, null));
        when(gateway.retrievePaymentIntent("pi_canceled")).thenReturn(pi("canceled"));
        when(gateway.retrievePaymentIntent("pi_ok")).thenReturn(pi("succeeded"));
        when(gateway.retrievePaymentIntent("pi_alive")).thenReturn(pi("processing"));
        when(gateway.retrievePaymentIntent("pi_unreadable")).thenThrow(new ApiConnectionException("timeout"));

        for (String id : List.of("pi_canceled", "pi_ok", "pi_alive", "pi_unreadable")) {
            rail.onCommissionWebhook("payment_intent.payment_failed", a.getId(), id, "7", "card_declined");
        }

        verify(ledger).commissionCharged(a.getId(), "pi_ok", "7", "cancel-refused");
        verify(ledger, never()).commissionCharged(eq(a.getId()), eq("pi_canceled"), any(), any());
        verify(alerts).raiseOnce(eq("AMEND_COMM_PI_UNCANCEL_pi_alive"), anyString(),
                org.mockito.ArgumentMatchers.anyMap());
        verify(alerts).raiseOnce(eq("AMEND_COMM_PI_UNCANCEL_pi_unreadable"), anyString(),
                org.mockito.ArgumentMatchers.anyMap());
        verify(alerts, never()).raiseOnce(eq("AMEND_COMM_PI_UNCANCEL_pi_canceled"), anyString(),
                org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("PaymentIntent neuve créée pendant que la modification était close : annulée, jamais enregistrée")
    void chargeCard_closedDuringCharge_cancelsNewPaymentIntent() throws Exception {
        commissionPendingByCard();
        when(ledger.find(a.getId())).thenReturn(Optional.of(a));
        when(users.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(pi("requires_action"));
        BidAmendmentEntity cancelled = new BidAmendmentEntity();
        BidAmendmentTestSupport.setId(cancelled, a.getId());
        cancelled.setRail(BidSupplementRail.CASH);
        cancelled.setCurrency("EUR");
        cancelled.setNetAmount(a.getNetAmount());
        cancelled.setGrossAmount(a.getGrossAmount());
        cancelled.setCommissionAmount(a.getCommissionAmount());
        cancelled.setAddedWeightKg(a.getAddedWeightKg());
        cancelled.setExpiresAt(a.getExpiresAt());
        cancelled.setStatus(BidAmendmentStatus.CANCELLED);
        when(ledger.commissionRequiresAction(eq(a.getId()), eq("pi_c"), any())).thenReturn(cancelled);

        rail.chargeCard(a.getId());

        verify(gateway).cancelPaymentIntent("pi_c");
        verify(refunds).settle(a.getId());
    }

    @Test
    @DisplayName("le rail est CASH, sans méthode de création ni d'expiration propre")
    void railIdentity() {
        assertThat(rail.rail()).isEqualTo(BidSupplementRail.CASH);
        assertThat(rail.mayExpire(a)).isTrue();
        assertThat(rail.onCreated(a, mock(UserEntity.class)).status()).isEqualTo(BidAmendmentStatus.PROPOSED);
    }
}
