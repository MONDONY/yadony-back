package com.yadony.api.payments.amendment;

import com.stripe.exception.InvalidRequestException;
import com.stripe.param.PaymentIntentCreateParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.StripeEventDispatcher;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidService;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.matching.dto.BidAmendmentResponse;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptRequest;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptResponse;
import com.yadony.api.payments.amendment.dto.AmendmentCreateResponse;
import com.yadony.api.payments.amendment.dto.CreateAmendmentRequest;
import com.yadony.api.payments.amendment.events.BidAmendmentAcceptedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentClosedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentRefundedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentRequestedEvent;
import com.yadony.api.payments.cash.CommissionChargedVia;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rail carte d'une modification de colis de bout en bout, sur un vrai PostgreSQL, Stripe simulé : le montant est
 * bloqué sur la carte de l'expéditeur à la création (PaymentIntent en capture manuelle), la demande n'est présentée au
 * voyageur qu'une fois l'autorisation confirmée, puis le montant est débité quand il accepte. Capture échouée
 * (autorisation expirée) : la demande est annulée et les kilos rendus. Demande close : l'autorisation est rendue après
 * le commit. Remboursement : Refund Stripe, puis {@code REFUNDED} au webhook {@code charge.refunded}.
 */
@SpringBootTest(properties = "yadony.bid-amendment.enabled=true")
@ActiveProfiles("e2e")
@RecordApplicationEvents
@DisplayName("BidAmendmentCardRail : montant bloqué puis débité à l'acceptation (PostgreSQL)")
class BidAmendmentCardRailIT {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @MockitoBean StripeAmendmentGateway gateway;
    /** Routeur du supplément, consulté avant celui des modifications : jamais d'appel réseau en test. */
    @MockitoBean com.yadony.api.payments.supplement.StripeSupplementGateway supplementGateway;
    @MockitoBean AdminAlertEscalator alerts;

    @Autowired BidAmendmentPaymentFlow flow;
    @Autowired BidAmendmentService service;
    @Autowired BidAmendmentCardRail cardRail;
    @Autowired BidAmendmentReconciliationJob job;
    @Autowired BidAmendmentRepository amendmentRepository;
    @Autowired StripeEventDispatcher dispatcher;
    @Autowired BidService bidService;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEvents events;

    // ── Création : montant bloqué ────────────────────────────────────────────

    @Test
    @DisplayName("create_returnsClientSecret_statusAuthorizationPending : PaymentIntent manuelle, brut, métadonnées, clé")
    void create_returnsClientSecret_statusAuthorizationPending() throws Exception {
        Parties p = parties("EUR");
        String piId = pi();
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(view(piId, "requires_payment_method", null));

        AmendmentCreateResponse created = flow.create(p.bidId, p.sender.getFirebaseUid(),
                new CreateAmendmentRequest(new BigDecimal("3"), null, PaymentMethod.STRIPE));

        UUID id = created.amendment().id();
        assertThat(created.amendment().status()).isEqualTo("AUTHORIZATION_PENDING");
        assertThat(created.clientSecret()).isEqualTo(piId + "_secret");
        assertThat(created.paymentMethodTypes()).containsExactly("card");
        assertThat(created.publishableKey()).isNotNull();
        // Vue de l'expéditeur : ni net ni commission.
        assertThat(created.amendment().netAmount()).isNull();
        assertThat(created.amendment().commissionAmount()).isNull();

        ArgumentCaptor<PaymentIntentCreateParams> params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        verify(gateway).createPaymentIntent(params.capture(), eq("amendment-pi-" + id));
        PaymentIntentCreateParams sent = params.getValue();
        // 3 kg × 10 € = 30 € net + 12 % = 33,60 € brut.
        assertThat(sent.getAmount()).isEqualTo(3360L);
        assertThat(sent.getCurrency()).isEqualTo("eur");
        assertThat(sent.getCaptureMethod()).isEqualTo(PaymentIntentCreateParams.CaptureMethod.MANUAL);
        assertThat(sent.getCustomer()).isEqualTo(p.sender.getStripeCustomerId());
        assertThat(sent.getTransferData()).as("compte plateforme").isNull();
        assertThat(sent.getApplicationFeeAmount()).isNull();
        assertThat(sent.getMetadata()).containsEntry("bid_amendment_id", id.toString())
                .containsEntry("bid_id", p.bidId.toString())
                .containsEntry("scope", "BID_AMENDMENT")
                .doesNotContainKeys("commission_purpose", "net_amount", "commission_amount");

        assertThat(column(id, "status")).isEqualTo("AUTHORIZATION_PENDING");
        assertThat(column(id, "stripe_payment_intent_id")).isEqualTo(piId);
        assertThat(column(id, "proposed_at")).isNull();
        assertThat(availableKg(p.announcementId)).as("kilos réservés dès la création").isEqualByComparingTo("3.00");
        assertThat(events.stream(BidAmendmentRequestedEvent.class)).as("pas encore présentée au voyageur").isEmpty();
    }

    @Test
    @DisplayName("création : PaymentIntent impossible à créer → demande annulée, kilos rendus, erreur rendue")
    void create_paymentIntentFails_cancelsAndReleases() throws Exception {
        Parties p = parties("EUR");
        when(gateway.createPaymentIntent(any(), anyString()))
                .thenThrow(new InvalidRequestException("boom", null, "req_1", "parameter_invalid", 400, null));

        Throwable thrown = catchThrowable(() -> flow.create(p.bidId, p.sender.getFirebaseUid(),
                new CreateAmendmentRequest(new BigDecimal("3"), null, PaymentMethod.STRIPE)));

        assertThat(thrown).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("amendment-payment-creation-failed"));
        UUID id = onlyAmendment(p.bidId);
        assertThat(column(id, "status")).isEqualTo("CANCELLED");
        assertThat(column(id, "closed_reason")).isEqualTo("AUTHORIZATION_NOT_CONFIRMED");
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("6.00");
    }

    @Test
    @DisplayName("colis XOF : la carte n'est jamais proposée (pas de versement Connect en zone CFA)")
    void create_xofBid_cardNotOffered() {
        Parties p = parties("XOF");

        Throwable thrown = catchThrowable(() -> flow.create(p.bidId, p.sender.getFirebaseUid(),
                new CreateAmendmentRequest(new BigDecimal("1"), null, PaymentMethod.STRIPE)));

        assertThat(thrown).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("amendment-rail-unavailable"));
        assertThat(service.options(p.bidId, p.sender.getFirebaseUid()).paymentMethods()).containsExactly("CASH");
    }

    // ── Autorisation confirmée ───────────────────────────────────────────────

    @Test
    @DisplayName("confirmAuthorization_requiresCapture_movesToProposedAndNotifies : PROPOSED, proposed_at, voyageur prévenu")
    void confirmAuthorization_requiresCapture_movesToProposedAndNotifies() throws Exception {
        Parties p = parties("EUR");
        String piId = pi();
        UUID id = createCard(p, piId);
        assertThat(bidService.getBidById(p.bidId, p.traveler.getFirebaseUid()).amendments())
                .as("montant pas encore bloqué : invisible du voyageur").isEmpty();
        when(gateway.retrievePaymentIntent(piId)).thenReturn(view(piId, "requires_capture", null));

        BidAmendmentResponse confirmed = flow.confirmAuthorization(p.bidId, id, p.sender.getFirebaseUid());

        assertThat(confirmed.status()).isEqualTo("PROPOSED");
        assertThat(confirmed.netAmount()).as("vue de l'expéditeur").isNull();
        assertThat(column(id, "status")).isEqualTo("PROPOSED");
        assertThat(column(id, "proposed_at")).isNotNull();
        assertThat(events.stream(BidAmendmentRequestedEvent.class)).singleElement().satisfies(e -> {
            assertThat(e.amendmentId()).isEqualTo(id);
            assertThat(e.travelerId()).isEqualTo(p.traveler.getId());
            assertThat(e.grossAmount()).isEqualByComparingTo("33.60");
            assertThat(e.timezone()).isEqualTo("Europe/Paris");
        });
        assertThat(auditActions(id)).containsExactly("BID_AMENDMENT_REQUESTED", "BID_AMENDMENT_AUTHORIZED");
        assertThat(bidService.getBidById(p.bidId, p.traveler.getFirebaseUid()).amendments())
                .extracting(BidAmendmentResponse::status).containsExactly("PROPOSED");

        // Rejeu : sans effet.
        flow.confirmAuthorization(p.bidId, id, p.sender.getFirebaseUid());
        assertThat(events.stream(BidAmendmentRequestedEvent.class)).hasSize(1);
    }

    @Test
    @DisplayName("confirmAuthorization_notAuthorized_staysPending : PaymentIntent pas encore autorisée, rien ne bouge")
    void confirmAuthorization_notAuthorized_staysPending() throws Exception {
        Parties p = parties("EUR");
        String piId = pi();
        UUID id = createCard(p, piId);
        when(gateway.retrievePaymentIntent(piId)).thenReturn(view(piId, "requires_action", null));

        BidAmendmentResponse view = flow.confirmAuthorization(p.bidId, id, p.sender.getFirebaseUid());

        assertThat(view.status()).isEqualTo("AUTHORIZATION_PENDING");
        assertThat(column(id, "proposed_at")).isNull();
        assertThat(events.stream(BidAmendmentRequestedEvent.class)).isEmpty();
        assertThat(catchThrowable(() -> flow.confirmAuthorization(p.bidId, id, p.traveler.getFirebaseUid())))
                .as("seul l'expéditeur confirme").isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(403));
    }

    @Test
    @DisplayName("webhook_amountCapturableUpdated_isIdempotent : deux webhooks, une seule présentation au voyageur")
    void webhook_amountCapturableUpdated_isIdempotent() throws Exception {
        Parties p = parties("EUR");
        String piId = pi();
        UUID id = createCard(p, piId);
        String capturable = cardEvent("payment_intent.amount_capturable_updated", piId, id, p.bidId,
                "requires_capture", null);

        assertThat(dispatcher.dispatch(capturable)).isTrue();
        assertThat(dispatcher.dispatch(capturable)).as("rejeu").isTrue();

        assertThat(column(id, "status")).isEqualTo("PROPOSED");
        assertThat(events.stream(BidAmendmentRequestedEvent.class)).hasSize(1);
        assertThat(auditActions(id)).containsOnlyOnce("BID_AMENDMENT_AUTHORIZED");
        // Le séquestre du colis n'a rien vu : aucune ligne de paiement créée ou modifiée.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE bid_id = ?", Integer.class, p.bidId))
                .isZero();
    }

    // ── Acceptation : capture ────────────────────────────────────────────────

    @Test
    @DisplayName("accept_captures_andAccepts : capture amendment-capture-<id>, ACCEPTED, charge enregistrée")
    void accept_captures_andAccepts() throws Exception {
        Parties p = parties("EUR");
        String piId = pi();
        UUID id = proposedCard(p, piId);
        when(gateway.capturePaymentIntent(piId, "amendment-capture-" + id)).thenReturn(view(piId, "succeeded", "ch_1"));

        AmendmentAcceptResponse accepted = flow.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(null, null));

        assertThat(accepted.amendment().status()).isEqualTo("ACCEPTED");
        assertThat(accepted.amendment().netAmount()).as("vue du voyageur").isEqualByComparingTo("30.00");
        verify(gateway).capturePaymentIntent(piId, "amendment-capture-" + id);
        assertThat(column(id, "stripe_charge_id")).isEqualTo("ch_1");
        assertThat(column(id, "accepted_at")).isNotNull();
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("3.00");
        assertThat(events.stream(BidAmendmentAcceptedEvent.class)).singleElement()
                .satisfies(e -> assertThat(e.amendmentId()).isEqualTo(id));
        assertThat(auditActions(id)).endsWith("BID_AMENDMENT_ACCEPTED");

        // Rejeu du webhook payment_intent.succeeded : sans effet.
        assertThat(dispatcher.dispatch(cardEvent("payment_intent.succeeded", piId, id, p.bidId, "succeeded", "ch_1")))
                .isTrue();
        assertThat(events.stream(BidAmendmentAcceptedEvent.class)).hasSize(1);
        verify(gateway, after(500).never()).cancelPaymentIntent(anyString());
    }

    @Test
    @DisplayName("accept_captureFails_cancelsAndReleases : autorisation expirée, CANCELLED CAPTURE_FAILED, kilos rendus, 409")
    void accept_captureFails_cancelsAndReleases() throws Exception {
        Parties p = parties("EUR");
        String piId = pi();
        UUID id = proposedCard(p, piId);
        when(gateway.capturePaymentIntent(piId, "amendment-capture-" + id)).thenThrow(new InvalidRequestException(
                "This PaymentIntent could not be captured because it has a status of canceled.", null, "req_2",
                "payment_intent_unexpected_state", 400, null));
        when(gateway.retrievePaymentIntent(piId)).thenReturn(view(piId, "canceled", null));
        when(gateway.cancelPaymentIntent(piId)).thenReturn(view(piId, "canceled", null));

        Throwable thrown = catchThrowable(() -> flow.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(null, null)));

        assertThat(thrown).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo("amendment-capture-failed");
            assertThat(e.getStatus().value()).isEqualTo(409);
        });
        assertThat(column(id, "status")).isEqualTo("CANCELLED");
        assertThat(column(id, "closed_reason")).isEqualTo("CAPTURE_FAILED");
        assertThat(column(id, "accepted_at")).isNull();
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("6.00");
        assertThat(events.stream(BidAmendmentClosedEvent.Cancelled.class)).singleElement().satisfies(e -> {
            assertThat(e.closedReason()).isEqualTo(BidAmendmentClosedReason.CAPTURE_FAILED);
            assertThat(e.previousStatus()).isEqualTo(BidAmendmentStatus.PROPOSED);
        });
        assertThat(events.stream(BidAmendmentAcceptedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("capture réussie sur une demande close entre-temps (annulée par l'expéditeur) : montant remboursé")
    void accept_capturedAfterCancel_refunds() throws Exception {
        Parties p = parties("EUR");
        String piId = pi();
        UUID id = proposedCard(p, piId);
        AmendmentAcceptResponse prepared = service.accept(p.bidId, id, p.traveler.getFirebaseUid(),
                new AmendmentAcceptRequest(null, null));
        assertThat(prepared.amendment().status()).as("capture après le commit").isEqualTo("PROPOSED");
        when(gateway.cancelPaymentIntent(piId)).thenThrow(new InvalidRequestException("already captured", null,
                "req_3", "payment_intent_unexpected_state", 400, null));
        when(gateway.retrievePaymentIntent(piId)).thenReturn(view(piId, "succeeded", "ch_late"));
        when(gateway.capturePaymentIntent(piId, "amendment-capture-" + id)).thenReturn(view(piId, "succeeded", "ch_late"));
        when(gateway.findRefund(piId, id.toString())).thenReturn(Optional.empty());
        when(gateway.createRefund(eq(piId), anyMap(), anyString())).thenReturn("re_late");
        service.cancel(p.bidId, id, p.sender.getFirebaseUid());

        Throwable thrown = catchThrowable(() -> cardRail.afterAcceptCommit(prepared));

        assertThat(thrown).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("amendment-not-pending"));
        verify(gateway, timeout(3000).times(1)).createRefund(eq(piId), anyMap(), eq("amendment-refund-" + id));
        awaitColumn(id, "stripe_refund_id", "re_late");
        assertThat(column(id, "status")).isEqualTo("CANCELLED");
        assertThat(column(id, "stripe_charge_id")).isEqualTo("ch_late");
        assertThat(column(id, "accepted_at")).isNull();

        assertThat(dispatcher.dispatch(chargeRefunded(piId, id, p.bidId, "re_late"))).isTrue();
        assertThat(column(id, "refunded_at")).isNotNull();
        assertThat(column(id, "status")).isEqualTo("CANCELLED");
        assertThat(events.stream(BidAmendmentRefundedEvent.class)).singleElement().satisfies(e -> {
            assertThat(e.recipientId()).isEqualTo(p.sender.getId());
            assertThat(e.amount()).isEqualByComparingTo("33.60");
            assertThat(e.commissionOnly()).isFalse();
        });
        verify(gateway, after(500).times(1)).createRefund(eq(piId), anyMap(), anyString());
    }

    // ── Clôture : autorisation rendue après le commit ────────────────────────

    @Test
    @DisplayName("decline_cancelsPaymentIntent : refus du voyageur, autorisation annulée après le commit")
    void decline_cancelsPaymentIntent() throws Exception {
        Parties p = parties("EUR");
        String piId = pi();
        UUID id = proposedCard(p, piId);
        when(gateway.cancelPaymentIntent(piId)).thenReturn(view(piId, "canceled", null));

        BidAmendmentResponse declined = service.decline(p.bidId, id, p.traveler.getFirebaseUid());

        assertThat(declined.status()).isEqualTo("DECLINED");
        verify(gateway, timeout(3000).times(1)).cancelPaymentIntent(piId);
        verify(gateway, never()).capturePaymentIntent(anyString(), anyString());
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("6.00");
        awaitAudit(id, "BID_AMENDMENT_AUTHORIZATION_RELEASED");
    }

    @Test
    @DisplayName("annulation de l'autorisation refusée car la PaymentIntent a été capturée : remboursée")
    void closed_cancelRefusedBecauseCaptured_refunds() throws Exception {
        Parties p = parties("EUR");
        String piId = pi();
        UUID id = proposedCard(p, piId);
        when(gateway.cancelPaymentIntent(piId)).thenThrow(new InvalidRequestException("captured", null, "req_4",
                "payment_intent_unexpected_state", 400, null));
        when(gateway.retrievePaymentIntent(piId)).thenReturn(view(piId, "succeeded", "ch_2"));
        when(gateway.findRefund(piId, id.toString())).thenReturn(Optional.empty());
        when(gateway.createRefund(eq(piId), anyMap(), anyString())).thenReturn("re_2");

        service.decline(p.bidId, id, p.traveler.getFirebaseUid());

        verify(gateway, timeout(3000).times(1)).createRefund(eq(piId), anyMap(), eq("amendment-refund-" + id));
        awaitColumn(id, "stripe_charge_id", "ch_2");
        assertThat(column(id, "status")).isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("annulation de l'autorisation impossible et PaymentIntent toujours vivante : alerte admin")
    void closed_cancelRefusedStillLive_alerts() throws Exception {
        Parties p = parties("EUR");
        String piId = pi();
        UUID id = proposedCard(p, piId);
        when(gateway.cancelPaymentIntent(piId)).thenThrow(new InvalidRequestException("nope", null, "req_5",
                "resource_missing", 400, null));
        when(gateway.retrievePaymentIntent(piId)).thenReturn(view(piId, "requires_capture", null));

        service.decline(p.bidId, id, p.traveler.getFirebaseUid());

        verify(alerts, timeout(3000)).raiseOnce(eq("AMEND_PI_UNCANCELED_" + id), anyString(), anyMap());
    }

    @Test
    @DisplayName("authorizationWindowElapsed_cancels : autorisation jamais confirmée, filet → CANCELLED, kilos et autorisation rendus")
    void authorizationWindowElapsed_cancels() throws Exception {
        Parties p = parties("EUR");
        String piId = pi();
        UUID id = createCard(p, piId);
        when(gateway.cancelPaymentIntent(piId)).thenReturn(view(piId, "canceled", null));
        jdbc.update("UPDATE bid_amendments SET created_at = created_at - interval '31 minutes' WHERE id = ?", id);

        job.run();

        assertThat(column(id, "status")).isEqualTo("CANCELLED");
        assertThat(column(id, "closed_reason")).isEqualTo("AUTHORIZATION_NOT_CONFIRMED");
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("6.00");
        verify(gateway, timeout(3000).times(1)).cancelPaymentIntent(piId);

        // Autorisation qui arrive après l'échéance : la demande close ne revient pas.
        assertThat(dispatcher.dispatch(cardEvent("payment_intent.amount_capturable_updated", piId, id, p.bidId,
                "requires_capture", null))).isTrue();
        assertThat(column(id, "status")).isEqualTo("CANCELLED");
        assertThat(events.stream(BidAmendmentRequestedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("autorisation confirmée après l'échéance mais avant le filet : la demande est close, pas présentée")
    void confirmAuthorization_afterWindow_closesInstead() throws Exception {
        Parties p = parties("EUR");
        String piId = pi();
        UUID id = createCard(p, piId);
        jdbc.update("UPDATE bid_amendments SET created_at = created_at - interval '31 minutes' WHERE id = ?", id);
        when(gateway.retrievePaymentIntent(piId)).thenReturn(view(piId, "requires_capture", null));
        when(gateway.cancelPaymentIntent(piId)).thenReturn(view(piId, "canceled", null));

        BidAmendmentResponse view = flow.confirmAuthorization(p.bidId, id, p.sender.getFirebaseUid());

        assertThat(view.status()).isEqualTo("CANCELLED");
        assertThat(column(id, "closed_reason")).isEqualTo("AUTHORIZATION_NOT_CONFIRMED");
        assertThat(events.stream(BidAmendmentRequestedEvent.class)).isEmpty();
        verify(gateway, timeout(3000).times(1)).cancelPaymentIntent(piId);
    }

    @Test
    @DisplayName("annulée depuis AUTHORIZATION_PENDING : jamais montrée au voyageur, visible de l'expéditeur")
    void cancelledFromAuthorizationPending_invisibleToTraveler() throws Exception {
        Parties p = parties("EUR");
        String piId = pi();
        UUID id = createCard(p, piId);
        when(gateway.cancelPaymentIntent(piId)).thenReturn(view(piId, "canceled", null));

        service.cancel(p.bidId, id, p.sender.getFirebaseUid());

        assertThat(column(id, "status")).isEqualTo("CANCELLED");
        assertThat(bidService.getBidById(p.bidId, p.traveler.getFirebaseUid()).amendments()).isEmpty();
        assertThat(bidService.getBidById(p.bidId, p.sender.getFirebaseUid()).amendments())
                .extracting(BidAmendmentResponse::status).containsExactly("CANCELLED");
        verify(gateway, timeout(3000).times(1)).cancelPaymentIntent(piId);
    }

    @Test
    @DisplayName("PaymentIntent annulée chez Stripe pendant que le voyageur réfléchit : CANCELLED CAPTURE_FAILED")
    void webhook_canceledWhileProposed_cancels() throws Exception {
        Parties p = parties("EUR");
        String piId = pi();
        UUID id = proposedCard(p, piId);
        when(gateway.cancelPaymentIntent(piId)).thenReturn(view(piId, "canceled", null));

        assertThat(dispatcher.dispatch(cardEvent("payment_intent.canceled", piId, id, p.bidId, "canceled", null)))
                .isTrue();

        assertThat(column(id, "status")).isEqualTo("CANCELLED");
        assertThat(column(id, "closed_reason")).isEqualTo("CAPTURE_FAILED");
        assertThat(availableKg(p.announcementId)).isEqualByComparingTo("6.00");
    }

    // ── Remboursement d'une modification acquise ─────────────────────────────

    @Test
    @DisplayName("non apportée : Refund amendment-refund-<id>, puis REFUNDED au webhook charge.refunded")
    void notBrought_card_refundedOnWebhook() throws Exception {
        Parties p = parties("EUR");
        String piId = pi();
        UUID id = acceptedCard(p, piId, "ch_3");
        when(gateway.findRefund(piId, id.toString())).thenReturn(Optional.empty());
        when(gateway.createRefund(eq(piId), anyMap(), anyString())).thenReturn("re_3");

        BidAmendmentResponse view = service.notBrought(p.bidId, id, p.traveler.getFirebaseUid());

        assertThat(view.status()).isEqualTo("REFUND_PENDING");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(gateway, timeout(3000).times(1)).createRefund(eq(piId), metadata.capture(),
                eq("amendment-refund-" + id));
        assertThat(metadata.getValue()).containsEntry("bid_amendment_id", id.toString())
                .containsEntry("scope", "BID_AMENDMENT");
        awaitColumn(id, "stripe_refund_id", "re_3");
        assertThat(column(id, "status")).as("REFUNDED seulement au webhook").isEqualTo("REFUND_PENDING");

        String refunded = chargeRefunded(piId, id, p.bidId, "re_3");
        assertThat(dispatcher.dispatch(refunded)).isTrue();
        assertThat(dispatcher.dispatch(refunded)).as("rejeu").isTrue();

        assertThat(column(id, "status")).isEqualTo("REFUNDED");
        assertThat(column(id, "refunded_at")).isNotNull();
        assertThat(events.stream(BidAmendmentRefundedEvent.class)).singleElement().satisfies(e -> {
            assertThat(e.recipientId()).isEqualTo(p.sender.getId());
            assertThat(e.amount()).isEqualByComparingTo("33.60");
            assertThat(e.commissionOnly()).isFalse();
        });
        assertThat(auditActions(id)).containsOnlyOnce("BID_AMENDMENT_REFUNDED");
    }

    @Test
    @DisplayName("modification acquise remboursée depuis le Dashboard Stripe : alerte, rien ne change")
    void chargeRefunded_onLiveAmendment_alerts() throws Exception {
        Parties p = parties("EUR");
        String piId = pi();
        UUID id = acceptedCard(p, piId, "ch_4");

        assertThat(dispatcher.dispatch(chargeRefunded(piId, id, p.bidId, "re_dash"))).isTrue();

        assertThat(column(id, "status")).isEqualTo("ACCEPTED");
        verify(alerts).raiseOnce(eq("AMEND_CARD_REF_LIVE_" + id), anyString(), anyMap());
    }

    @Test
    @DisplayName("litige bancaire sur la charge d'une modification : alerte, jamais le traitement des litiges du colis")
    void dispute_onCardCharge_alertsOnly() throws Exception {
        Parties p = parties("EUR");
        String piId = pi();
        UUID id = acceptedCard(p, piId, "ch_5");

        assertThat(dispatcher.dispatch(event("charge.dispute.created", "{\"id\":\"dp_1\",\"object\":\"dispute\","
                + "\"charge\":\"ch_5\",\"payment_intent\":\"" + piId + "\",\"status\":\"needs_response\"}")))
                .isTrue();

        verify(alerts).raiseOnce(eq("AMEND_CHARGEBACK_" + id), anyString(), anyMap());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chargebacks WHERE bid_id = ?", Integer.class, p.bidId))
                .isZero();
        assertThat(column(id, "status")).isEqualTo("ACCEPTED");
    }

    // ── Aides ────────────────────────────────────────────────────────────────

    private record Parties(UserEntity traveler, UserEntity sender, UUID announcementId, UUID bidId) {
    }

    /** Trajet carte et espèces à 10 €/kg (6 000 XOF/kg), 6 kg libres, voyageur au compte Connect complet. */
    private Parties parties(String currency) {
        UserEntity traveler = persistUser(true);
        UserEntity sender = persistUser(false);
        UUID announcementId = persistAnnouncement(traveler.getId(), currency);
        UUID bidId = persistBid(announcementId, sender.getId(), currency);
        return new Parties(traveler, sender, announcementId, bidId);
    }

    /** Création par le flux : PaymentIntent créée et enregistrée, modification AUTHORIZATION_PENDING. */
    private UUID createCard(Parties p, String piId) throws Exception {
        when(gateway.createPaymentIntent(any(), anyString())).thenReturn(view(piId, "requires_payment_method", null));
        return flow.create(p.bidId, p.sender.getFirebaseUid(),
                new CreateAmendmentRequest(new BigDecimal("3"), null, PaymentMethod.STRIPE)).amendment().id();
    }

    /** Créée, puis autorisée (webhook {@code amount_capturable_updated}) : PROPOSED. */
    private UUID proposedCard(Parties p, String piId) throws Exception {
        UUID id = createCard(p, piId);
        assertThat(dispatcher.dispatch(cardEvent("payment_intent.amount_capturable_updated", piId, id, p.bidId,
                "requires_capture", null))).isTrue();
        assertThat(column(id, "status")).isEqualTo("PROPOSED");
        return id;
    }

    /** Proposée puis acceptée, capture réussie : ACCEPTED. */
    private UUID acceptedCard(Parties p, String piId, String chargeId) throws Exception {
        UUID id = proposedCard(p, piId);
        when(gateway.capturePaymentIntent(piId, "amendment-capture-" + id)).thenReturn(view(piId, "succeeded", chargeId));
        assertThat(flow.accept(p.bidId, id, p.traveler.getFirebaseUid(), new AmendmentAcceptRequest(null, null))
                .amendment().status()).isEqualTo("ACCEPTED");
        return id;
    }

    private static String pi() {
        return "pi_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static PaymentIntentView view(String piId, String status, String chargeId) {
        return new PaymentIntentView(piId, status, 3360L, "eur", piId + "_secret", chargeId, List.of("card"));
    }

    private static String cardEvent(String type, String piId, UUID amendmentId, UUID bidId, String status,
                                    String chargeId) {
        return event(type, "{\"id\":\"" + piId + "\",\"object\":\"payment_intent\",\"status\":\"" + status
                + "\",\"amount\":3360,\"amount_capturable\":3360"
                + (chargeId == null ? "" : ",\"latest_charge\":\"" + chargeId + "\"")
                + ",\"metadata\":{\"bid_amendment_id\":\"" + amendmentId + "\",\"bid_id\":\"" + bidId
                + "\",\"scope\":\"BID_AMENDMENT\"}}");
    }

    private static String chargeRefunded(String piId, UUID amendmentId, UUID bidId, String refundId) {
        return event("charge.refunded", "{\"id\":\"ch_r\",\"object\":\"charge\",\"payment_intent\":\"" + piId
                + "\",\"amount\":3360,\"amount_refunded\":3360,\"refunds\":{\"object\":\"list\",\"data\":[{\"id\":\""
                + refundId + "\",\"object\":\"refund\"}]},\"metadata\":{\"bid_amendment_id\":\"" + amendmentId
                + "\",\"bid_id\":\"" + bidId + "\",\"scope\":\"BID_AMENDMENT\"}}");
    }

    private static String event(String type, String objectJson) {
        return "{\"id\":\"evt_" + UUID.randomUUID() + "\",\"object\":\"event\",\"api_version\":\""
                + com.stripe.Stripe.API_VERSION + "\",\"type\":\"" + type + "\",\"data\":{\"object\":" + objectJson
                + "}}";
    }

    private UUID onlyAmendment(UUID bidId) {
        return jdbc.queryForObject("SELECT id FROM bid_amendments WHERE bid_id = ?", UUID.class, bidId);
    }

    private String column(UUID id, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM bid_amendments WHERE id = ?", String.class, id);
    }

    private BigDecimal availableKg(UUID announcementId) {
        return jdbc.queryForObject("SELECT available_kg FROM announcements WHERE id = ?", BigDecimal.class,
                announcementId);
    }

    private List<String> auditActions(UUID id) {
        return jdbc.queryForList("SELECT action FROM audit_log WHERE entity_id = ? ORDER BY created_at, action",
                String.class, id);
    }

    private void awaitColumn(UUID id, String column, String expected) throws InterruptedException {
        Supplier<String> value = () -> column(id, column);
        for (int i = 0; i < 100 && !expected.equals(value.get()); i++) {
            Thread.sleep(100);
        }
        assertThat(value.get()).isEqualTo(expected);
    }

    private void awaitAudit(UUID id, String action) throws InterruptedException {
        for (int i = 0; i < 100 && !auditActions(id).contains(action); i++) {
            Thread.sleep(100);
        }
        assertThat(auditActions(id)).contains(action);
    }

    private UserEntity persistUser(boolean cardReady) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("amendment-card-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        user.setStripeCustomerId("cus_" + UUID.randomUUID());
        if (cardReady) {
            user.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
            user.setStripeAccountId("acct_" + UUID.randomUUID().toString().substring(0, 12));
        } else {
            user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        }
        return userRepository.saveAndFlush(user);
    }

    private UUID persistAnnouncement(UUID travelerId, String currency) {
        return tx.execute(status -> {
            AnnouncementEntity announcement = new AnnouncementEntity();
            announcement.setTravelerId(travelerId);
            announcement.setDepartureCity("Paris");
            announcement.setArrivalCity("Dakar");
            announcement.setDepartureDate(LocalDate.now().plusDays(10));
            announcement.setTransportMode(TransportMode.PLANE);
            announcement.setPickupAddressLabel("Gare du Nord, Paris");
            announcement.setPickupLat(new BigDecimal("48.880756"));
            announcement.setPickupLng(new BigDecimal("2.354987"));
            announcement.setDeliveryAddressLabel("Aéroport de Dakar");
            announcement.setDeliveryLat(new BigDecimal("14.670833"));
            announcement.setDeliveryLng(new BigDecimal("-17.073056"));
            announcement.setAvailableKg(new BigDecimal("6.00"));
            announcement.setTotalKg(new BigDecimal("60.00"));
            announcement.setCapacityUnit(CapacityUnit.SUITCASE_23KG);
            announcement.setPricePerKg("XOF".equals(currency) ? new BigDecimal("6000") : new BigDecimal("10.00"));
            announcement.setCurrency(currency);
            announcement.setTimezone("Europe/Paris");
            announcement.setStatus(AnnouncementStatus.ACTIVE);
            announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH, PaymentMethod.STRIPE));
            entityManager.persist(announcement);
            return announcement.getId();
        });
    }

    /** Colis espèces accepté (commission principale déjà prélevée), auquel l'expéditeur ajoute des kilos par carte. */
    private UUID persistBid(UUID announcementId, UUID senderId, String currency) {
        return tx.execute(status -> {
            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(senderId);
            bid.setPaymentMethod(PaymentMethod.CASH);
            bid.setCurrency(currency);
            bid.setWeightKg(new BigDecimal("2"));
            bid.setCommissionRate(new BigDecimal("0.12"));
            bid.setStatus(BidStatus.ACCEPTED);
            bid.setCommissionStatus(CommissionStatus.CHARGED);
            bid.setCommissionChargedVia(CommissionChargedVia.CARD);
            bid.setCommissionPaymentIntentId("pi_principal_" + UUID.randomUUID());
            bid.setQrToken("qr-" + UUID.randomUUID());
            bid.setTrackingNumber("DON-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            entityManager.persist(bid);
            return bid.getId();
        });
    }

    // ── Filet : autorisation dont l'annulation après commit s'est perdue ─────

    @Test
    @DisplayName("écouteur perdu : le filet rend l'autorisation après la grâce, pose le marqueur, puis ne relit plus Stripe")
    void job_releasesLostAuthorization_thenMarkerStopsStripeCalls() throws Exception {
        Parties p = parties("EUR");
        String piId = pi();
        UUID id = proposedCard(p, piId);
        when(gateway.cancelPaymentIntent(piId)).thenReturn(view(piId, "canceled", null));
        // Clôture sans événement (écouteur perdu) : écrite directement, il y a 10 min.
        jdbc.update("UPDATE bid_amendments SET status = 'DECLINED', closed_reason = 'DECLINED_BY_TRAVELER', "
                + "updated_at = updated_at - interval '10 minutes' WHERE id = ?", id);

        job.run();

        verify(gateway, times(1)).cancelPaymentIntent(piId);
        assertThat(column(id, "authorization_released_at")).isNotNull();
        assertThat(auditActions(id)).containsOnlyOnce("BID_AMENDMENT_AUTHORIZATION_RELEASED");

        jdbc.update("UPDATE bid_amendments SET updated_at = updated_at - interval '10 minutes' WHERE id = ?", id);
        job.run();
        verify(gateway, times(1)).cancelPaymentIntent(piId);
    }

    @Test
    @DisplayName("filet : marqueur déjà posé, clôture trop récente ou PaymentIntent capturée → aucun appel Stripe")
    void job_skipsReleasedRecentOrCaptured() throws Exception {
        Parties released = parties("EUR");
        String releasedPi = pi();
        UUID releasedId = proposedCard(released, releasedPi);
        jdbc.update("UPDATE bid_amendments SET status = 'EXPIRED', closed_reason = 'EXPIRED', "
                + "authorization_released_at = now(), updated_at = updated_at - interval '10 minutes' WHERE id = ?",
                releasedId);
        Parties recent = parties("EUR");
        String recentPi = pi();
        UUID recentId = proposedCard(recent, recentPi);
        jdbc.update("UPDATE bid_amendments SET status = 'DECLINED', closed_reason = 'DECLINED_BY_TRAVELER' "
                + "WHERE id = ?", recentId);
        Parties captured = parties("EUR");
        String capturedPi = pi();
        UUID capturedId = proposedCard(captured, capturedPi);
        jdbc.update("UPDATE bid_amendments SET status = 'CANCELLED', closed_reason = 'CANCELLED_BY_SENDER', "
                + "stripe_charge_id = 'ch_x', updated_at = updated_at - interval '10 minutes' WHERE id = ?", capturedId);

        job.run();

        verify(gateway, never()).cancelPaymentIntent(releasedPi);
        verify(gateway, never()).cancelPaymentIntent(recentPi);
        verify(gateway, never()).cancelPaymentIntent(capturedPi);
    }

    @Test
    @DisplayName("filet : seules les modifications closes, non capturées, non rendues, au-delà de la grâce et dans la fenêtre")
    void findCardIdsAwaitingRelease_selectsOnlyClosedUnreleased() throws Exception {
        UUID lost = closedCard("DECLINED", "10 minutes", null, null);
        UUID released = closedCard("EXPIRED", "10 minutes", "now()", null);
        UUID recent = closedCard("DECLINED", "0 minutes", null, null);
        UUID captured = closedCard("CANCELLED", "10 minutes", null, "'ch_q'");
        UUID tooOld = closedCard("DECLINED", "9 days", null, null);
        UUID proposedOld = closedCard("PROPOSED", "10 minutes", null, null);
        UUID pendingOld = closedCard("AUTHORIZATION_PENDING", "10 minutes", null, null);
        UUID acceptedOld = closedCard("ACCEPTED", "10 minutes", null, null);

        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        List<UUID> found = amendmentRepository.findCardIdsAwaitingRelease(BidAmendmentCardLedger.CLOSED_UNACCEPTED,
                now.minusDays(8), now.minusMinutes(5), org.springframework.data.domain.PageRequest.of(0, 1000));

        assertThat(found).contains(lost)
                .doesNotContain(released, recent, captured, tooOld, proposedOld, pendingOld, acceptedOld);

        // Le filet ne touche pas aux demandes ouvertes ou acquises, même anciennes.
        job.run();
        for (UUID untouched : List.of(proposedOld, pendingOld, acceptedOld)) {
            verify(gateway, never()).cancelPaymentIntent(column(untouched, "stripe_payment_intent_id"));
            assertThat(column(untouched, "authorization_released_at")).isNull();
        }
    }

    /** Modification carte proposée puis amenée par SQL au statut voulu, modifiée il y a {@code age}. */
    private UUID closedCard(String status, String age, String releasedAt, String chargeId) throws Exception {
        Parties p = parties("EUR");
        String piId = pi();
        UUID id = proposedCard(p, piId);
        boolean open = Set.of("PROPOSED", "AUTHORIZATION_PENDING").contains(status);
        jdbc.update("UPDATE bid_amendments SET status = ?, closed_reason = ?, authorization_released_at = "
                + (releasedAt == null ? "NULL" : releasedAt) + ", stripe_charge_id = "
                + (chargeId == null ? "NULL" : chargeId) + ", accepted_at = "
                + ("ACCEPTED".equals(status) ? "now()" : "NULL")
                + ", updated_at = updated_at - interval '" + age + "' WHERE id = ?",
                status, open || "ACCEPTED".equals(status) ? null : "EXPIRED", id);
        return id;
    }

    @Test
    @DisplayName("filet : une ligne déjà essayée sans succès passe après les lignes jamais essayées (pas de famine)")
    void findCardIdsAwaitingRelease_failedAttemptsLast() throws Exception {
        UUID failing = closedCard("DECLINED", "20 minutes", null, null);
        jdbc.update("UPDATE bid_amendments SET release_attempted_at = now() WHERE id = ?", failing);
        UUID fresh = closedCard("DECLINED", "10 minutes", null, null);

        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        List<UUID> found = amendmentRepository.findCardIdsAwaitingRelease(BidAmendmentCardLedger.CLOSED_UNACCEPTED,
                now.minusDays(8), now.minusMinutes(5), org.springframework.data.domain.PageRequest.of(0, 1000));

        assertThat(found).contains(failing, fresh);
        assertThat(found.indexOf(fresh)).as("jamais essayée d'abord").isLessThan(found.indexOf(failing));
    }
}
