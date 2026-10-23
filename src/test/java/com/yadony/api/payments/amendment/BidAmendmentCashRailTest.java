package com.yadony.api.payments.amendment;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.InvalidRequestException;
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

    private BidAmendmentCashRail rail;
    private BidAmendmentEntity a;
    private UserEntity traveler;

    @BeforeEach
    void setUp() {
        rail = new BidAmendmentCashRail(service, collector, activeCurrencyResolver, gateway, ledger, refunds,
                amendments, amendmentItems, users, new BidAmendmentWindow(new BidAmendmentProperties()));
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

        verify(ledger).commissionFailed(a.getId(), null, "no-commission-card");
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
        verify(ledger, never()).commissionFailed(any(), any(), any());
    }

    @Test
    @DisplayName("débit réussi : commission enregistrée, modification acquise")
    void chargeCard_succeeded_recordsCommission() throws Exception {
        commissionPendingByCard();
        when(ledger.find(a.getId())).thenReturn(Optional.of(a));
        when(users.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(gateway.createPaymentIntent(any(), eq("amendment_commission_" + a.getId() + "_v1")))
                .thenReturn(pi("succeeded"));

        AmendmentAcceptResponse r = rail.chargeCard(a.getId());

        verify(ledger).commissionCharged(a.getId(), "pi_c", "create");
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
        when(ledger.commissionRequiresAction(a.getId(), "pi_c")).thenReturn(a);

        assertThat(rail.chargeCard(a.getId()).commissionClientSecret()).isNull();
        assertThat(catchThrowable(() -> rail.chargeCard(a.getId()))).isInstanceOfSatisfying(
                YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("amendment-commission-card-declined"));
        verify(ledger).commissionFailed(a.getId(), "pi_c", "status-requires_payment_method");
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
        when(ledger.commissionRequiresAction(a.getId(), "pi_c")).thenReturn(cancelled);

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
        when(ledger.commissionRequiresAction(a.getId(), "pi_c")).thenReturn(a);

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
        rail.onCommissionWebhook("payment_intent.succeeded", a.getId(), "pi_c", "unknown");
        rail.onCommissionWebhook("payment_intent.canceled", a.getId(), "pi_c", "unknown");
        rail.onCommissionWebhook("payment_intent.created", a.getId(), "pi_c", "unknown");

        verify(ledger).commissionCharged(a.getId(), "pi_c", "webhook");
        verify(ledger).commissionFailed(a.getId(), "pi_c", "canceled");
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("webhook : 3DS échouée, la PaymentIntent est annulée ; annulation refusée ou sans id : sans suite")
    void webhook_paymentFailed_cancelsPaymentIntentQuietly() throws Exception {
        when(ledger.commissionFailed(a.getId(), "pi_c", "payment_failed:card_declined")).thenReturn(true);
        when(ledger.commissionFailed(a.getId(), null, "payment_failed:card_declined")).thenReturn(true);
        when(ledger.commissionFailed(a.getId(), "pi_old", "payment_failed:card_declined")).thenReturn(false);
        when(gateway.cancelPaymentIntent("pi_c")).thenThrow(new ApiConnectionException("timeout"));

        rail.onCommissionWebhook("payment_intent.payment_failed", a.getId(), "pi_c", "card_declined");
        rail.onCommissionWebhook("payment_intent.payment_failed", a.getId(), null, "card_declined");
        rail.onCommissionWebhook("payment_intent.payment_failed", a.getId(), "pi_old", "card_declined");

        verify(gateway).cancelPaymentIntent("pi_c");
        verify(gateway, never()).cancelPaymentIntent("pi_old");
    }

    @Test
    @DisplayName("le rail est CASH, sans méthode de création ni d'expiration propre")
    void railIdentity() {
        assertThat(rail.rail()).isEqualTo(BidSupplementRail.CASH);
        assertThat(rail.mayExpire(a)).isTrue();
        assertThat(rail.onCreated(a, mock(UserEntity.class)).status()).isEqualTo(BidAmendmentStatus.PROPOSED);
    }
}
