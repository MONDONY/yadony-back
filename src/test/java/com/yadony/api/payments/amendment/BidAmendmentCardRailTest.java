package com.yadony.api.payments.amendment;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.InvalidRequestException;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.BidAmendmentResponse;
import com.yadony.api.payments.PaymentService;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptResponse;
import com.yadony.api.payments.amendment.dto.AmendmentCreateResponse;
import com.yadony.api.payments.amendment.events.BidAmendmentClosedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentRefundRequestedEvent;
import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Rail carte : branches d'erreur Stripe (réseau, refus passager, PaymentIntent close), sans base. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BidAmendmentCardRail : erreurs Stripe")
class BidAmendmentCardRailTest {

    @Mock StripeAmendmentGateway gateway;
    @Mock BidAmendmentCardLedger ledger;
    @Mock BidAmendmentRepository amendments;
    @Mock BidAmendmentItemRepository items;
    @Mock UserRepository users;
    @Mock PaymentService paymentService;
    @Mock BidAmendmentWindow window;
    @Mock AdminAlertEscalator alerts;

    private BidAmendmentCardRail rail;
    private BidAmendmentEntity a;
    private UserEntity sender;

    @BeforeEach
    void setUp() {
        rail = new BidAmendmentCardRail(gateway, ledger, amendments, items, users, paymentService, window, alerts,
                "pk_test");
        sender = new UserEntity();
        BidAmendmentTestSupport.setId(sender, UUID.randomUUID());
        a = new BidAmendmentEntity();
        BidAmendmentTestSupport.setId(a, UUID.randomUUID());
        a.setBidId(UUID.randomUUID());
        a.setSenderId(sender.getId());
        a.setRail(BidSupplementRail.STRIPE);
        a.setStatus(BidAmendmentStatus.PROPOSED);
        a.setCurrency("EUR");
        a.setNetAmount(new BigDecimal("30.00"));
        a.setCommissionAmount(new BigDecimal("3.60"));
        a.setGrossAmount(new BigDecimal("33.60"));
        a.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(10));
        a.setStripePaymentIntentId("pi_1");
        when(ledger.find(a.getId())).thenReturn(Optional.of(a));
        when(amendments.findById(a.getId())).thenReturn(Optional.of(a));
        when(items.findByAmendmentId(any())).thenReturn(List.of());
        when(window.now()).thenReturn(LocalDateTime.now(ZoneOffset.UTC));
        when(users.findById(sender.getId())).thenReturn(Optional.of(sender));
        when(users.findByFirebaseUid("uid-s")).thenReturn(Optional.of(sender));
    }

    private AmendmentAcceptResponse prepared() {
        return new AmendmentAcceptResponse(BidAmendmentViews.of(a, List.of(), false, window), null);
    }

    private static PaymentIntentView view(String status) {
        return new PaymentIntentView("pi_1", status, 3360L, "eur", "pi_1_secret", null, List.of("card"));
    }

    @Test
    @DisplayName("capture sans réponse et PaymentIntent illisible : 503, la demande reste proposée")
    void capture_networkFailure_503StaysProposed() throws Exception {
        when(gateway.capturePaymentIntent(eq("pi_1"), anyString())).thenThrow(new ApiConnectionException("timeout"));
        when(gateway.retrievePaymentIntent("pi_1")).thenThrow(new ApiConnectionException("timeout"));

        Throwable thrown = catchThrowable(() -> rail.afterAcceptCommit(prepared()));

        assertThat(thrown).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("stripe-unavailable"));
        verify(ledger, never()).captureFailed(any(), anyString());
    }

    @Test
    @DisplayName("capture refusée mais PaymentIntent encore capturable : 502, rien n'est annulé")
    void capture_refusedStillCapturable_502() throws Exception {
        when(gateway.capturePaymentIntent(eq("pi_1"), anyString()))
                .thenThrow(new InvalidRequestException("amount", null, "r", "parameter_invalid", 400, null));
        when(gateway.retrievePaymentIntent("pi_1")).thenReturn(view("requires_capture"));

        Throwable thrown = catchThrowable(() -> rail.afterAcceptCommit(prepared()));

        assertThat(thrown).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("stripe-error"));
        verify(ledger, never()).captureFailed(any(), anyString());
    }

    @Test
    @DisplayName("acceptation déjà acquise (rejeu) : rien n'est recapturé ; demande close : 409")
    void afterAccept_securedOrClosed() {
        a.setStatus(BidAmendmentStatus.ACCEPTED);
        assertThat(rail.afterAcceptCommit(prepared()).amendment().status()).isEqualTo("ACCEPTED");

        a.setStatus(BidAmendmentStatus.CANCELLED);
        assertThat(catchThrowable(() -> rail.afterAcceptCommit(prepared())))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-not-pending"));
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("acceptation sans PaymentIntent : 409, jamais de capture")
    void accept_withoutPaymentIntent_409() {
        a.setStripePaymentIntentId(null);

        assertThat(catchThrowable(() -> rail.accept(a, sender, null)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-not-pending"));
    }

    @Test
    @DisplayName("création : Stripe injoignable → demande abandonnée, 503")
    void create_networkFailure_abandons() throws Exception {
        a.setStatus(BidAmendmentStatus.AUTHORIZATION_PENDING);
        a.setStripePaymentIntentId(null);
        when(paymentService.ensureStripeCustomer(sender)).thenReturn("cus_1");
        when(gateway.createPaymentIntent(any(), anyString())).thenThrow(new ApiConnectionException("timeout"));
        AmendmentCreateResponse created = AmendmentCreateResponse.of(BidAmendmentViews.of(a, List.of(), true, window));

        Throwable thrown = catchThrowable(() -> rail.afterCreateCommit(created));

        assertThat(thrown).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("stripe-unavailable"));
        verify(ledger).abandonAuthorization(a.getId());
    }

    @Test
    @DisplayName("création : demande close pendant la création de la PaymentIntent → autorisation rendue, 409")
    void create_closedMeanwhile_releasesAnd409() throws Exception {
        a.setStatus(BidAmendmentStatus.AUTHORIZATION_PENDING);
        a.setStripePaymentIntentId(null);
        when(paymentService.ensureStripeCustomer(sender)).thenReturn("cus_1");
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(view("requires_payment_method"));
        AmendmentCreateResponse created = AmendmentCreateResponse.of(BidAmendmentViews.of(a, List.of(), true, window));
        when(ledger.storePaymentIntent(a.getId(), "pi_1")).thenAnswer(inv -> {
            a.setStatus(BidAmendmentStatus.CANCELLED);
            a.setStripePaymentIntentId("pi_1");
            return Optional.of(a);
        });
        when(gateway.cancelPaymentIntent("pi_1")).thenReturn(view("canceled"));

        Throwable thrown = catchThrowable(() -> rail.afterCreateCommit(created));

        assertThat(thrown).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("amendment-not-pending"));
        verify(gateway).cancelPaymentIntent("pi_1");
    }

    @Test
    @DisplayName("confirmation : modification espèces → 422 ; autre colis → 404 ; Stripe injoignable → 503")
    void confirmAuthorization_errors() throws Exception {
        a.setRail(BidSupplementRail.CASH);
        assertThat(catchThrowable(() -> rail.confirmAuthorization(a.getBidId(), a.getId(), "uid-s")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-rail-unavailable"));
        assertThat(catchThrowable(() -> rail.confirmAuthorization(UUID.randomUUID(), a.getId(), "uid-s")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("amendment-not-found"));

        a.setRail(BidSupplementRail.STRIPE);
        a.setStatus(BidAmendmentStatus.AUTHORIZATION_PENDING);
        when(gateway.retrievePaymentIntent("pi_1")).thenThrow(new ApiConnectionException("timeout"));
        assertThat(catchThrowable(() -> rail.confirmAuthorization(a.getBidId(), a.getId(), "uid-s")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("stripe-unavailable"));
    }

    @Test
    @DisplayName("confirmation déjà présentée : aucune relecture Stripe")
    void confirmAuthorization_alreadyProposed_noStripeCall() {
        BidAmendmentResponse view = rail.confirmAuthorization(a.getBidId(), a.getId(), "uid-s");

        assertThat(view.status()).isEqualTo("PROPOSED");
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("webhooks : échec de paiement tracé, type inconnu sans effet")
    void webhooks_paymentFailedAuditedOtherIgnored() {
        rail.onPaymentIntentWebhook("payment_intent.payment_failed", a.getId(), "pi_1", "requires_payment_method",
                null, "card_declined");
        rail.onPaymentIntentWebhook("payment_intent.processing", a.getId(), "pi_1", "processing", null, null);

        verify(ledger).auditOnly(eq(a.getId()), eq("BID_AMENDMENT_AUTHORIZATION_FAILED"), anyMap());
        verify(ledger, never()).authorize(any(), any(), any(), any());
        verify(ledger, never()).captured(any(), any(), any(), any());
    }

    @Test
    @DisplayName("autorisation à rendre : rien si encore ouverte, déjà capturée ou sans PaymentIntent ; relecture illisible → alerte")
    void releaseAuthorization_guardsAndUnreadable() throws Exception {
        rail.releaseAuthorization(a.getId());
        a.setStatus(BidAmendmentStatus.DECLINED);
        a.setStripeChargeId("ch_1");
        rail.releaseAuthorization(a.getId());
        verifyNoInteractions(gateway);

        a.setStripeChargeId(null);
        when(gateway.cancelPaymentIntent("pi_1")).thenThrow(new ApiConnectionException("timeout"));
        when(gateway.retrievePaymentIntent("pi_1")).thenThrow(new ApiConnectionException("timeout"));
        rail.releaseAuthorization(a.getId());
        verify(alerts).raiseOnce(eq("AMEND_PI_UNCANCELED_" + a.getId()), anyString(), anyMap());
    }

    @Test
    @DisplayName("écouteur : clôture d'une modification acquise ou d'un autre rail ignorée ; erreur avalée et tracée")
    void listener_filtersAndSwallows() {
        BidAmendmentCardRail cardRail = mock(BidAmendmentCardRail.class);
        BidAmendmentCardRefunds refunds = mock(BidAmendmentCardRefunds.class);
        BidAmendmentCardListener listener = new BidAmendmentCardListener(cardRail, refunds);
        UUID id = UUID.randomUUID();

        listener.onClosed(new BidAmendmentClosedEvent.NotBrought(id, UUID.randomUUID(), null, null,
                BidSupplementRail.STRIPE, BidAmendmentStatus.ACCEPTED, BidAmendmentClosedReason.NOT_BROUGHT));
        listener.onClosed(new BidAmendmentClosedEvent.Declined(id, UUID.randomUUID(), null, null,
                BidSupplementRail.CASH, BidAmendmentStatus.PROPOSED, BidAmendmentClosedReason.DECLINED_BY_TRAVELER));
        listener.onRefundRequested(new BidAmendmentRefundRequestedEvent(id, UUID.randomUUID(),
                BidSupplementRail.CASH, "NOT_BROUGHT"));
        verifyNoInteractions(cardRail, refunds);

        doThrow(new IllegalStateException("boom")).when(cardRail).releaseAuthorization(id);
        when(refunds.refund(id)).thenThrow(new IllegalStateException("boom"));
        listener.onClosed(new BidAmendmentClosedEvent.Declined(id, UUID.randomUUID(), null, null,
                BidSupplementRail.STRIPE, BidAmendmentStatus.PROPOSED, BidAmendmentClosedReason.DECLINED_BY_TRAVELER));
        listener.onRefundRequested(new BidAmendmentRefundRequestedEvent(id, UUID.randomUUID(),
                BidSupplementRail.STRIPE, "NOT_BROUGHT"));
        verify(cardRail).releaseAuthorization(id);
        verify(refunds).refund(id);
    }

    @Test
    @DisplayName("remboursement : Refund impossible → trace et alerte, rien n'est enregistré")
    void refunds_failure_alerts() throws Exception {
        BidAmendmentCardRefunds refunds = new BidAmendmentCardRefunds(gateway, ledger, alerts);
        when(ledger.beginRefund(a.getId())).thenReturn(new BidAmendmentCardLedger.RefundClaim(
                BidAmendmentCardLedger.RefundDecision.PROCEED, a.getId(), a.getBidId(), "pi_1", null));
        when(gateway.findRefund("pi_1", a.getId().toString())).thenThrow(new ApiConnectionException("timeout"));

        assertThat(refunds.refund(a.getId())).isFalse();

        verify(ledger).auditOnly(eq(a.getId()), eq("BID_AMENDMENT_REFUND_FAILED"), anyMap());
        verify(alerts).raiseOnce(eq("AMEND_CARD_REFUND_FAIL_" + a.getId()), anyString(), anyMap());
        verify(ledger, never()).refundSubmitted(any(), any());
    }
}
