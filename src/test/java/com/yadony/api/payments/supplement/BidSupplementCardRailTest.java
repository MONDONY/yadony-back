package com.yadony.api.payments.supplement;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.param.PaymentIntentCreateParams;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.BidSupplementResponse;
import com.yadony.api.payments.PaymentService;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;
import com.yadony.api.payments.supplement.dto.SupplementAcceptResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.payments.supplement.BidSupplementPricingTest.setId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Rail carte : PaymentIntent du supplément, réutilisation, confirmation, annulation. */
@ExtendWith(MockitoExtension.class)
class BidSupplementCardRailTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    @Mock StripeSupplementGateway gateway;
    @Mock BidSupplementCardLedger ledger;
    @Mock BidSupplementRepository supplements;
    @Mock UserRepository users;
    @Mock PaymentService paymentService;
    @Mock StorageService storage;

    private BidSupplementCardRail rail;
    private BidSupplementEntity s;
    private UserEntity sender;

    @BeforeEach
    void setUp() {
        BidSupplementWindow window = new BidSupplementWindow(new BidSupplementProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        rail = new BidSupplementCardRail(gateway, ledger, supplements, users, paymentService, storage, window,
                "pk_test_1");
        sender = new UserEntity();
        setId(sender, UUID.randomUUID());
        sender.setFirebaseUid("uid-sender");
        s = new BidSupplementEntity();
        setId(s, UUID.randomUUID());
        s.setBidId(UUID.randomUUID());
        s.setSenderId(sender.getId());
        s.setTravelerId(UUID.randomUUID());
        s.setRail(BidSupplementRail.STRIPE);
        s.setStatus(BidSupplementStatus.PAYMENT_PENDING);
        s.setStripePiAttempt(1);
        s.setCurrency("EUR");
        s.setNetAmount(new BigDecimal("20.00"));
        s.setCommissionAmount(new BigDecimal("1.10"));
        s.setGrossAmount(new BigDecimal("21.10"));
        s.setReasonCode(BidSupplementReason.GRID_ITEM);
        s.setPhotoKey("tracking/" + s.getBidId() + "/1.jpg");
        s.setExpiresAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusHours(1));
        lenient().when(ledger.find(s.getId())).thenReturn(Optional.of(s));
        lenient().when(users.findById(sender.getId())).thenReturn(Optional.of(sender));
        lenient().when(storage.generatePresignedUrl(any(), any())).thenReturn("https://signed");
    }

    private static PaymentIntentView pi(String id, String status) {
        return new PaymentIntentView(id, status, 2110L, "eur", id + "_secret_x", "ch_" + id,
                List.of("card", "paypal"));
    }

    @Test
    void rail_estLaCarte_etAcceptPasseEnAttenteDePaiementSansAppelStripe() throws Exception {
        assertThat(rail.rail()).isEqualTo(BidSupplementRail.STRIPE);
        s.setStatus(BidSupplementStatus.PROPOSED);

        SupplementAcceptResponse r = rail.accept(s, sender);

        verify(ledger).startPayment(s);
        assertThat(r.clientSecret()).isNull();
        assertThat(r.publishableKey()).isEqualTo("pk_test_1");
        assertThat(r.supplement().netAmount()).as("l'expéditeur ne voit jamais le net").isNull();
        verify(gateway, never()).createPaymentIntent(any(), any());
    }

    @Test
    void paiement_creeLaPaymentIntent_captureAutomatiqueSansReferenceAuColis() throws Exception {
        when(paymentService.paypalEnabled()).thenReturn(true);
        when(paymentService.ensureStripeCustomer(sender)).thenReturn("cus_1");
        ArgumentCaptor<PaymentIntentCreateParams> params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        when(gateway.createPaymentIntent(params.capture(), eq("supplement-pi-" + s.getId() + "-v1")))
                .thenReturn(pi("pi_1", "requires_payment_method"));
        BidSupplementEntity stored = copyWithPi("pi_1");
        when(ledger.storePaymentIntent(s.getId(), 1, "pi_1")).thenReturn(stored);

        SupplementAcceptResponse r = rail.afterAcceptCommit(new SupplementAcceptResponse(
                BidSupplementViews.of(s, true, storage, LocalDateTime.now()), "STRIPE", null, null,
                s.getGrossAmount(), "EUR", "pk_test_1"));

        PaymentIntentCreateParams p = params.getValue();
        assertThat(p.getAmount()).isEqualTo(2110L);
        assertThat(p.getCurrency()).isEqualTo("eur");
        assertThat(p.getCustomer()).isEqualTo("cus_1");
        assertThat(p.getCaptureMethod()).as("capture automatique").isNull();
        assertThat(p.getTransferData()).isNull();
        assertThat(p.getApplicationFeeAmount()).isNull();
        assertThat(p.getOnBehalfOf()).isNull();
        assertThat(p.getSetupFutureUsage()).isNull();
        assertThat(p.getPaymentMethodTypes()).containsExactly("card", "paypal");
        assertThat(p.getPaymentMethodOptions().getPaypal()).isNotNull();
        assertThat(p.getStatementDescriptorSuffix()).isEqualTo("YADONY");
        assertThat(p.getMetadata()).containsEntry("bid_supplement_id", s.getId().toString())
                .containsEntry("scope", "BID_SUPPLEMENT")
                .containsEntry("supplement_bid_id", s.getBidId().toString())
                .as("l'expéditeur lit les métadonnées avec le client_secret : ni commission ni voyageur")
                .doesNotContainKeys("bid_id", "payment_id", "wallet_topup", "commission_purpose",
                        "commission_minor", "commission_amount", "traveler_id", "net_amount");

        assertThat(r.clientSecret()).isEqualTo("pi_1_secret_x");
        assertThat(r.paymentMethodTypes()).containsExactly("card", "paypal");
        assertThat(r.publishableKey()).isEqualTo("pk_test_1");
        assertThat(r.amount()).isEqualByComparingTo("21.10");
        assertThat(r.currency()).isEqualTo("EUR");
        assertThat(r.rail()).isEqualTo("STRIPE");
    }

    @Test
    void paiement_paypalDesactive_carteSeule_sansOptionsPaypal() throws Exception {
        // YADONY-FLUTTER-PROD-11 : « paypal » sans la capacité Stripe fait refuser le PaymentIntent.
        when(paymentService.paypalEnabled()).thenReturn(false);
        when(paymentService.ensureStripeCustomer(sender)).thenReturn("cus_1");
        ArgumentCaptor<PaymentIntentCreateParams> params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        when(gateway.createPaymentIntent(params.capture(), eq("supplement-pi-" + s.getId() + "-v1")))
                .thenReturn(new PaymentIntentView("pi_1", "requires_payment_method", 2110L, "eur",
                        "pi_1_secret_x", null, List.of("card")));
        when(ledger.storePaymentIntent(s.getId(), 1, "pi_1")).thenReturn(copyWithPi("pi_1"));

        SupplementAcceptResponse r = rail.afterAcceptCommit(new SupplementAcceptResponse(
                BidSupplementViews.of(s, true, storage, LocalDateTime.now()), "STRIPE", null, null,
                s.getGrossAmount(), "EUR", "pk_test_1"));

        PaymentIntentCreateParams p = params.getValue();
        assertThat(p.getPaymentMethodTypes()).containsExactly("card");
        assertThat(p.getPaymentMethodOptions()).isNull();
        assertThat(r.paymentMethodTypes()).containsExactly("card");
    }

    @Test
    void paiement_relance_paypalDesactive_repliSurLaCarteSeule() throws Exception {
        when(paymentService.paypalEnabled()).thenReturn(false);
        s.setStripePaymentIntentId("pi_1");
        when(gateway.retrievePaymentIntent("pi_1")).thenReturn(new PaymentIntentView("pi_1", "requires_action",
                2110L, "eur", "pi_1_secret_x", null, null));

        assertThat(rail.pay(s.getId()).paymentMethodTypes()).containsExactly("card");
    }

    @Test
    void paiement_relance_reutiliseLaPaymentIntentEncorePayable() throws Exception {
        when(paymentService.paypalEnabled()).thenReturn(true);
        s.setStripePaymentIntentId("pi_1");
        when(gateway.retrievePaymentIntent("pi_1")).thenReturn(new PaymentIntentView("pi_1", "requires_action",
                2110L, "eur", "pi_1_secret_x", null, null));

        SupplementAcceptResponse r = rail.pay(s.getId());

        assertThat(r.clientSecret()).isEqualTo("pi_1_secret_x");
        assertThat(r.paymentMethodTypes()).as("repli sur les moyens du principal").containsExactly("card", "paypal");
        verify(gateway, never()).createPaymentIntent(any(), any());
    }

    @Test
    void paiement_paymentIntentDejaReussie_enregistreLePaiementSansNouveauSecret() throws Exception {
        s.setStripePaymentIntentId("pi_1");
        when(gateway.retrievePaymentIntent("pi_1")).thenReturn(pi("pi_1", "succeeded"));
        BidSupplementEntity paid = copyWithPi("pi_1");
        paid.setStatus(BidSupplementStatus.PAID);
        when(ledger.find(s.getId())).thenReturn(Optional.of(s), Optional.of(paid));

        SupplementAcceptResponse r = rail.pay(s.getId());

        verify(ledger).markPaid(s.getId(), "pi_1", "ch_pi_1", "accept");
        assertThat(r.clientSecret()).isNull();
        assertThat(r.supplement().status()).isEqualTo("PAID");
    }

    @Test
    void paiement_paymentIntentAnnulee_nouvelleTentativeAvecNouvelleCle() throws Exception {
        s.setStripePaymentIntentId("pi_old");
        when(gateway.retrievePaymentIntent("pi_old")).thenReturn(pi("pi_old", "canceled"));
        BidSupplementEntity renewed = copyWithPi(null);
        renewed.setStripePiAttempt(2);
        when(ledger.find(s.getId())).thenReturn(Optional.of(s), Optional.of(renewed));
        when(paymentService.ensureStripeCustomer(sender)).thenReturn("cus_1");
        when(gateway.createPaymentIntent(any(), eq("supplement-pi-" + s.getId() + "-v2")))
                .thenReturn(pi("pi_2", "requires_payment_method"));
        BidSupplementEntity stored = copyWithPi("pi_2");
        when(ledger.storePaymentIntent(s.getId(), 2, "pi_2")).thenReturn(stored);

        assertThat(rail.pay(s.getId()).clientSecret()).isEqualTo("pi_2_secret_x");
        verify(ledger).renewAttempt(s.getId(), "pi_old");
    }

    @Test
    void paiement_paymentIntentEnTraitement_409() throws Exception {
        s.setStripePaymentIntentId("pi_1");
        when(gateway.retrievePaymentIntent("pi_1")).thenReturn(pi("pi_1", "processing"));

        assertError(() -> rail.pay(s.getId()), HttpStatus.CONFLICT, "supplement-payment-processing");
    }

    @Test
    void paiement_demandeQuiNAttendPlus_409_ouIntrouvable_404() {
        s.setStatus(BidSupplementStatus.DECLINED);
        assertError(() -> rail.pay(s.getId()), HttpStatus.CONFLICT, "supplement-not-pending");

        UUID unknown = UUID.randomUUID();
        assertError(() -> rail.pay(unknown), HttpStatus.NOT_FOUND, "supplement-not-found");
    }

    @Test
    void paiement_demandeCloseDurantLaCreation_paymentIntentAnnuleeEt409() throws Exception {
        when(paymentService.ensureStripeCustomer(sender)).thenReturn("cus_1");
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(pi("pi_1", "requires_payment_method"));
        BidSupplementEntity closed = copyWithPi("pi_1");
        closed.setStatus(BidSupplementStatus.DECLINED);
        when(ledger.storePaymentIntent(s.getId(), 1, "pi_1")).thenReturn(closed);
        when(gateway.cancelPaymentIntent("pi_1")).thenReturn(pi("pi_1", "canceled"));

        assertError(() -> rail.pay(s.getId()), HttpStatus.CONFLICT, "supplement-not-pending");
        verify(gateway).cancelPaymentIntent("pi_1");
        verify(ledger).auditOnly(eq(s.getId()), eq("BID_SUPPLEMENT_PI_CANCELED"), anyMap());
    }

    @Test
    void paiement_autreTentativeEnregistreeEntreTemps_relitPuisAbandonneEn409() throws Exception {
        when(paymentService.ensureStripeCustomer(sender)).thenReturn("cus_1");
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(pi("pi_1", "requires_payment_method"));
        BidSupplementEntity other = copyWithPi(null);
        when(ledger.storePaymentIntent(eq(s.getId()), anyInt(), eq("pi_1"))).thenReturn(other);

        assertError(() -> rail.pay(s.getId()), HttpStatus.CONFLICT, "payment-busy");
    }

    @Test
    void paiement_stripeInjoignable_502StripeUnavailable_sinon502Creation() throws Exception {
        when(paymentService.ensureStripeCustomer(sender)).thenReturn("cus_1");
        when(gateway.createPaymentIntent(any(), anyString()))
                .thenThrow(new ApiConnectionException("reset"))
                .thenThrow(new InvalidRequestException("bad", null, null, null, 400, null));

        assertError(() -> rail.pay(s.getId()), HttpStatus.BAD_GATEWAY, "stripe-unavailable");
        assertError(() -> rail.pay(s.getId()), HttpStatus.BAD_GATEWAY, "supplement-payment-creation-failed");
    }

    @Test
    void paiement_lectureStripeEnEchec_502() throws Exception {
        s.setStripePaymentIntentId("pi_1");
        when(gateway.retrievePaymentIntent("pi_1"))
                .thenThrow(new ApiConnectionException("reset"))
                .thenThrow(new InvalidRequestException("bad", null, null, null, 400, null));

        assertError(() -> rail.pay(s.getId()), HttpStatus.BAD_GATEWAY, "stripe-unavailable");
        assertError(() -> rail.pay(s.getId()), HttpStatus.BAD_GATEWAY, "stripe-error");
    }

    @Test
    void paiement_expediteurIntrouvable_404() {
        when(users.findById(sender.getId())).thenReturn(Optional.empty());
        assertError(() -> rail.pay(s.getId()), HttpStatus.NOT_FOUND, "user-not-found");
    }

    // ── confirm-payment ──────────────────────────────────────────────────────

    @Test
    void confirmation_paymentIntentReussie_enregistreLePaiement() throws Exception {
        s.setStripePaymentIntentId("pi_1");
        when(users.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(supplements.findByIdAndBidId(s.getId(), s.getBidId())).thenReturn(Optional.of(s));
        when(gateway.retrievePaymentIntent("pi_1")).thenReturn(pi("pi_1", "succeeded"));

        BidSupplementResponse view = rail.confirmPayment(s.getBidId(), s.getId(), "uid-sender");

        verify(ledger).markPaid(s.getId(), "pi_1", "ch_pi_1", "confirm-payment");
        assertThat(view.netAmount()).isNull();
        assertThat(view.grossAmount()).isEqualByComparingTo("21.10");
    }

    @Test
    void confirmation_paiementPasEncoreAbouti_ouDejaPaye_sansEffet() throws Exception {
        s.setStripePaymentIntentId("pi_1");
        when(users.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(supplements.findByIdAndBidId(s.getId(), s.getBidId())).thenReturn(Optional.of(s));
        when(gateway.retrievePaymentIntent("pi_1")).thenReturn(pi("pi_1", "requires_payment_method"));

        assertThat(rail.confirmPayment(s.getBidId(), s.getId(), "uid-sender").status()).isEqualTo("PAYMENT_PENDING");

        s.setStatus(BidSupplementStatus.PAID);
        assertThat(rail.confirmPayment(s.getBidId(), s.getId(), "uid-sender").status()).isEqualTo("PAID");
        verify(gateway).retrievePaymentIntent("pi_1");
        verify(ledger, never()).markPaid(any(), any(), any(), any());
    }

    @Test
    void confirmation_autreUtilisateur_403_inconnu_404_nonAuthentifie_401() {
        UserEntity other = new UserEntity();
        setId(other, UUID.randomUUID());
        when(users.findByFirebaseUid("uid-other")).thenReturn(Optional.of(other));
        when(supplements.findByIdAndBidId(s.getId(), s.getBidId())).thenReturn(Optional.of(s));

        assertError(() -> rail.confirmPayment(s.getBidId(), s.getId(), "uid-other"), HttpStatus.FORBIDDEN, "forbidden");
        UUID unknown = UUID.randomUUID();
        assertError(() -> rail.confirmPayment(s.getBidId(), unknown, "uid-other"), HttpStatus.NOT_FOUND,
                "supplement-not-found");
        assertError(() -> rail.confirmPayment(s.getBidId(), s.getId(), "uid-ghost"), HttpStatus.UNAUTHORIZED,
                "user-not-found");
    }

    // ── Webhook et annulation ────────────────────────────────────────────────

    @Test
    void webhookReussi_delegueAuRegistre() {
        rail.onPaymentSucceeded(s.getId(), "pi_1", "ch_1");
        verify(ledger).markPaid(s.getId(), "pi_1", "ch_1", "webhook");
    }

    @Test
    void annulation_demandeCloseAvecPaymentIntent_annuleeChezStripe() throws Exception {
        s.setStatus(BidSupplementStatus.EXPIRED);
        s.setStripePaymentIntentId("pi_1");
        when(gateway.cancelPaymentIntent("pi_1")).thenReturn(pi("pi_1", "canceled"));

        rail.cancelPendingPayment(s.getId());

        verify(gateway).cancelPaymentIntent("pi_1");
        verify(ledger, never()).markPaid(any(), any(), any(), any());
    }

    @Test
    void annulation_refuseeCarLePaiementAAbouti_paiementTardifEnregistre() throws Exception {
        s.setStatus(BidSupplementStatus.EXPIRED);
        s.setStripePaymentIntentId("pi_1");
        when(gateway.cancelPaymentIntent("pi_1"))
                .thenThrow(new InvalidRequestException("status succeeded", null, null, null, 400, null));
        when(gateway.retrievePaymentIntent("pi_1")).thenReturn(pi("pi_1", "succeeded"));

        rail.cancelPendingPayment(s.getId());

        verify(ledger).markPaid(s.getId(), "pi_1", "ch_pi_1", "cancel-refused");
    }

    @Test
    void annulation_refuseePuisLectureEnEchec_ouEnTraitement_sansEffet() throws Exception {
        s.setStatus(BidSupplementStatus.CANCELLED);
        s.setStripePaymentIntentId("pi_1");
        when(gateway.cancelPaymentIntent("pi_1"))
                .thenThrow(new InvalidRequestException("nope", null, null, null, 400, null));
        when(gateway.retrievePaymentIntent("pi_1"))
                .thenReturn(pi("pi_1", "processing"))
                .thenThrow(new ApiConnectionException("reset"));

        rail.cancelPendingPayment(s.getId());
        rail.cancelPendingPayment(s.getId());

        verify(ledger, never()).markPaid(any(), any(), any(), any());
    }

    @Test
    void annulation_sansPaymentIntent_ouDemandePayee_rienAAnnuler() throws Exception {
        s.setStatus(BidSupplementStatus.DECLINED);
        rail.cancelPendingPayment(s.getId());
        s.setStripePaymentIntentId("pi_1");
        s.setStatus(BidSupplementStatus.PAID);
        rail.cancelPendingPayment(s.getId());
        s.setStatus(BidSupplementStatus.DECLINED);
        s.setPaidAt(LocalDateTime.now());
        rail.cancelPendingPayment(s.getId());
        rail.cancelPendingPayment(UUID.randomUUID());

        verify(gateway, never()).cancelPaymentIntent(any());
    }

    private BidSupplementEntity copyWithPi(String piId) {
        BidSupplementEntity c = new BidSupplementEntity();
        setId(c, s.getId());
        c.setBidId(s.getBidId());
        c.setSenderId(s.getSenderId());
        c.setTravelerId(s.getTravelerId());
        c.setRail(s.getRail());
        c.setStatus(s.getStatus());
        c.setStripePiAttempt(s.getStripePiAttempt());
        c.setStripePaymentIntentId(piId);
        c.setCurrency(s.getCurrency());
        c.setNetAmount(s.getNetAmount());
        c.setCommissionAmount(s.getCommissionAmount());
        c.setGrossAmount(s.getGrossAmount());
        c.setReasonCode(s.getReasonCode());
        c.setPhotoKey(s.getPhotoKey());
        c.setExpiresAt(s.getExpiresAt());
        return c;
    }

    private static void assertError(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getErrorCode()).isEqualTo(code);
        });
    }
}
