package com.yadony.api.payments;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.PermissionException;
import com.stripe.exception.RateLimitException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.TransferCreateParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Versement « réserver → appeler → finaliser » (PROD_CHECKLIST 9.23), Stripe simulé. */
class StripeReleaseExecutorTest {

    private final StripeReleaseIntentRepository intents = ReleaseTestSupport.intentRepository();
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final StripeTransferLookup lookup = mock(StripeTransferLookup.class);
    private final AuditService audit = mock(AuditService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AdminAlertEscalator alerts = mock(AdminAlertEscalator.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-11T10:00:00Z"), ZoneOffset.UTC);
    private final LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

    private StripeReleaseExecutor executor;
    private final UUID paymentId = UUID.randomUUID();
    private final UUID intentId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private PaymentEntity payment;
    /** Raison de blocage renvoyée par la garde simulée ({@code null} : rien ne bloque). */
    private String blockedBy;
    /** Réservation suivie : une prise de bail simulée lui pose le nouveau jeton. */
    private StripeReleaseIntentEntity current;

    @BeforeEach
    void setUp() {
        executor = new StripeReleaseExecutor(intents, payments, lookup, audit, events, alerts,
                TransactionOperations.withoutTransaction(), clock, intent -> blockedBy);
        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", paymentId);
        payment.setStripePaymentIntentId("pi_1");
        payment.setAmount(new BigDecimal("50.00"));
        payment.setCommissionAmount(new BigDecimal("6.00"));
        payment.setCurrency("EUR");
        payment.setStatus(PaymentStatus.RELEASED);
        when(payments.findById(paymentId)).thenReturn(Optional.of(payment));
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            if (current != null) current.setLeaseToken(inv.getArgument(3));
            return 1;
        }).when(intents).claimLease(any(), any(), any(), any());
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            if (current != null) current.setLeaseToken(inv.getArgument(3));
            return 1;
        }).when(intents).claimForAdmin(any(), any(), any(), any());
    }

    /** Envoi antérieur trop ancien pour être encore en vol. */
    private LocalDateTime settledSend() {
        return now.minus(StripeReleaseExecutor.SEND_SAFETY).minusMinutes(1);
    }

    private StripeReleaseExecutor.ReleaseSpec transferSpec() {
        return new StripeReleaseExecutor.ReleaseSpec(paymentId, StripeReleaseIntentEntity.Kind.TRANSFER, 4400L, "eur",
                "acct_1", "ch_1", "delivery", "ESCROW_RELEASED_TRANSFER", bidId, travelerId, senderId);
    }

    private StripeReleaseIntentEntity reserved(int attempts) {
        StripeReleaseIntentEntity intent = executor.reserve(transferSpec());
        ReflectionTestUtils.setField(intent, "id", intentId);
        intent.setAttempts(attempts);
        if (attempts > 1) {
            // Essai précédent : un Transfer.create est parti à l'instant (issue inconnue).
            intent.setCreateSentAt(now);
        }
        current = intent;
        return intent;
    }

    // ── Phase 1 : réservation ────────────────────────────────────────────────

    @Test
    void reserve_new_figeLesParametresEtPoseUnBail() {
        StripeReleaseIntentEntity intent = executor.reserve(transferSpec());

        assertThat(intent.getStatus()).isEqualTo(StripeReleaseIntentEntity.Status.PENDING);
        assertThat(intent.getAmountMinor()).isEqualTo(4400L);
        assertThat(intent.getDestination()).isEqualTo("acct_1");
        assertThat(intent.getSourceTransaction()).isEqualTo("ch_1");
        assertThat(intent.getAttempts()).isEqualTo(1);
        assertThat(intent.getLeaseUntil()).isEqualTo(now.plus(StripeReleaseExecutor.LEASE));
        verify(intents).save(intent);
    }

    @Test
    void reserve_reutiliseUneReservationAnnulee() {
        StripeReleaseIntentEntity old = new StripeReleaseIntentEntity();
        old.setPaymentId(paymentId);
        old.setStatus(StripeReleaseIntentEntity.Status.REVERTED);
        old.setLastError("insufficient funds");
        old.setAttempts(4);
        when(intents.findByPaymentId(paymentId)).thenReturn(Optional.of(old));

        StripeReleaseIntentEntity intent = executor.reserve(transferSpec());

        assertThat(intent).isSameAs(old);
        assertThat(intent.getStatus()).isEqualTo(StripeReleaseIntentEntity.Status.PENDING);
        assertThat(intent.getAttempts()).isEqualTo(1);
        assertThat(intent.getLastError()).isNull();
    }

    @Test
    void reserve_refuseSiUnVersementEstDejaParti() {
        StripeReleaseIntentEntity done = new StripeReleaseIntentEntity();
        done.setStatus(StripeReleaseIntentEntity.Status.DONE);
        done.setStripeObjectId("tr_old");
        when(intents.findByPaymentId(paymentId)).thenReturn(Optional.of(done));

        assertThatThrownBy(() -> executor.reserve(transferSpec()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aucun second versement");
    }

    // ── Phases 2 et 3 ────────────────────────────────────────────────────────

    @Test
    void execute_creeLeTransferAvecLaCleStable_puisFinalise() throws Exception {
        StripeReleaseIntentEntity intent = reserved(1);
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            Transfer created = mock(Transfer.class);
            when(created.getId()).thenReturn("tr_new");
            ArgumentCaptor<TransferCreateParams> params = ArgumentCaptor.forClass(TransferCreateParams.class);
            ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
            tr.when(() -> Transfer.create(params.capture(), options.capture())).thenReturn(created);

            StripeReleaseExecutor.Execution execution = executor.execute(intent);

            assertThat(execution.result()).isEqualTo(StripeReleaseExecutor.Result.RELEASED);
            assertThat(execution.paid()).isTrue();
            assertThat(options.getValue().getIdempotencyKey()).isEqualTo("transfer-" + paymentId + "-r1");
            assertThat(params.getValue().getAmount()).isEqualTo(4400L);
            assertThat(params.getValue().getDestination()).isEqualTo("acct_1");
            assertThat(params.getValue().getSourceTransaction()).isEqualTo("ch_1");
            assertThat(params.getValue().getTransferGroup()).isEqualTo("payment_" + paymentId);
        }
        verify(intents).markDone(intentId, "tr_new");
        verify(payments).recordStripeTransferId(paymentId, "tr_new");
        verify(audit).log(eq("PAYMENT"), eq(paymentId), eq("ESCROW_RELEASED_TRANSFER"), eq(bidId), anyMap());
        verify(events).publishEvent(any(PaymentReleasedEvent.class));
        verify(alerts).resolveOpen("RELEASE_PENDING_" + paymentId);
        verify(alerts).resolveOpen("RELEASE_BLOCKED_" + paymentId);
    }

    @Test
    void execute_transferDejaEmis_realigneSansSecondTransfer() throws Exception {
        StripeReleaseIntentEntity intent = reserved(1);
        when(lookup.findExistingTransfer(eq(paymentId), eq("acct_1"), any())).thenReturn(Optional.of("tr_old"));
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.REALIGNED);
            tr.verifyNoInteractions();
        }
        verify(payments).recordStripeTransferId(paymentId, "tr_old");
        verify(audit).log(eq("PAYMENT"), eq(paymentId), eq("TRANSFER_ALREADY_EXISTS_REALIGNED"), eq(bidId), anyMap());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void execute_dejaFinaliseAilleurs_niAuditNiNotification() throws Exception {
        StripeReleaseIntentEntity intent = reserved(1);
        when(intents.markDone(any(), any())).thenReturn(0);
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            tr.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenReturn(mock(Transfer.class));
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.NOT_OWNED);
        }
        verify(audit, never()).log(any(), any(), any(), any(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void execute_refusCertainDeStripe_annuleLeClaim() throws Exception {
        StripeReleaseIntentEntity intent = reserved(1);
        when(payments.revertUnpaidReleaseClaim(paymentId)).thenReturn(1);
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            tr.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new InvalidRequestException("insufficient funds", null, "req", null, 400, null));
            StripeReleaseExecutor.Execution execution = executor.execute(intent);
            assertThat(execution.result()).isEqualTo(StripeReleaseExecutor.Result.REVERTED);
            assertThat(execution.error()).contains("insufficient funds");
        }
        verify(intents).markReverted(eq(intentId), any(), anyString());
        verify(payments).revertUnpaidReleaseClaim(paymentId);
        verify(audit).log(eq("PAYMENT"), eq(paymentId), eq("ESCROW_RELEASE_REFUSED_BY_STRIPE"), eq(bidId), anyMap());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void execute_refusAlorsQueLeClaimAChange_traceSansRienEcraser() throws Exception {
        StripeReleaseIntentEntity intent = reserved(1);
        when(payments.revertUnpaidReleaseClaim(paymentId)).thenReturn(0);
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            tr.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new InvalidRequestException("bad", null, "req", null, 400, null));
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.REVERTED);
        }
        verify(audit).log(eq("PAYMENT"), eq(paymentId), eq("ESCROW_RELEASE_REFUSED_BY_STRIPE"), eq(bidId),
                org.mockito.ArgumentMatchers.argThat(m -> "false".equals(m.get("claimReverted"))));
    }

    @Test
    void execute_annulationConcurrente_neFaitRien() throws Exception {
        StripeReleaseIntentEntity intent = reserved(1);
        when(intents.markReverted(any(), any(), any())).thenReturn(0);
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            tr.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new InvalidRequestException("bad", null, "req", null, 400, null));
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.NOT_OWNED);
        }
        verify(payments, never()).revertUnpaidReleaseClaim(any());
    }

    @Test
    void execute_issueIncertaine_resteReserveEtRejoueraPlusTard() throws Exception {
        StripeReleaseIntentEntity intent = reserved(1);
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            tr.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new ApiConnectionException("Read timed out"));
            StripeReleaseExecutor.Execution execution = executor.execute(intent);
            assertThat(execution.result()).isEqualTo(StripeReleaseExecutor.Result.PENDING_RETRY);
            assertThat(execution.paid()).isFalse();
        }
        verify(intents).recordRetry(eq(intentId), any(), anyString(), eq(now.plus(Duration.ofMinutes(5))));
        verify(payments, never()).revertUnpaidReleaseClaim(any());
        verify(alerts, never()).raiseOnce(anyString(), anyString(), anyMap());
    }

    @Test
    void execute_issueIncertaineRepetee_alerteUneFois() throws Exception {
        StripeReleaseIntentEntity intent = reserved(StripeReleaseExecutor.ALERT_AFTER_ATTEMPTS);
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            tr.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new ApiException("boom", "req", null, 500, null));
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.PENDING_RETRY);
        }
        verify(alerts).raiseOnce(eq("RELEASE_PENDING_" + paymentId), anyString(), anyMap());
    }

    @Test
    void execute_alerteEnEchec_nEmpechePasLaReprise() throws Exception {
        StripeReleaseIntentEntity intent = reserved(5);
        when(alerts.raiseOnce(anyString(), anyString(), anyMap())).thenThrow(new IllegalStateException("db"));
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            tr.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new ApiConnectionException("reset"));
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.PENDING_RETRY);
        }
    }

    @Test
    void execute_rechercheImpossibleAuPremierEssai_rendLeSequestreSansTransfer() throws Exception {
        StripeReleaseIntentEntity intent = reserved(1);
        when(lookup.findExistingTransfer(any(), any(), any())).thenThrow(new ApiConnectionException("timeout"));
        when(payments.revertUnpaidReleaseClaim(paymentId)).thenReturn(1);
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.REVERTED);
            tr.verifyNoInteractions();
        }
        verify(payments).revertUnpaidReleaseClaim(paymentId);
    }

    @Test
    void execute_rechercheImpossibleEnReprise_neRendJamaisLeSequestre() throws Exception {
        // Un essai précédent a pu créer le Transfer : la base ne revient pas en séquestre.
        StripeReleaseIntentEntity intent = reserved(2);
        when(lookup.findExistingTransfer(any(), any(), any()))
                .thenThrow(new InvalidRequestException("list failed", null, "req", null, 400, null));
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.PENDING_RETRY);
            tr.verifyNoInteractions();
        }
        verify(payments, never()).revertUnpaidReleaseClaim(any());
        verify(intents).recordRetry(eq(intentId), any(), anyString(), eq(now.plus(Duration.ofMinutes(10))));
    }

    @Test
    void execute_sousTransaction_refuseAvantToutAppelStripe() {
        StripeReleaseIntentEntity intent = reserved(1);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            assertThatThrownBy(() -> executor.execute(intent))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sous transaction");
            tr.verifyNoInteractions();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void execute_paiementIntrouvable_neFaitRien() {
        StripeReleaseIntentEntity intent = reserved(1);
        when(payments.findById(paymentId)).thenReturn(Optional.empty());
        assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.NOT_OWNED);
    }

    // ── Garde revérifiée juste avant l'appel ─────────────────────────────────

    @Test
    void gardeApparueAvantLAppel_premierEssai_aucunTransferEtSequestreRendu() throws Exception {
        StripeReleaseIntentEntity intent = reserved(1);
        blockedBy = "litige bancaire ouvert";
        when(payments.revertUnpaidReleaseClaim(paymentId)).thenReturn(1);
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            StripeReleaseExecutor.Execution execution = executor.execute(intent);
            assertThat(execution.result()).isEqualTo(StripeReleaseExecutor.Result.BLOCKED);
            tr.verifyNoInteractions();
        }
        verify(payments).revertUnpaidReleaseClaim(paymentId);
        verify(audit).log(eq("PAYMENT"), eq(paymentId), eq("ESCROW_RELEASE_BLOCKED_BEFORE_STRIPE"), eq(bidId), anyMap());
        verify(alerts).raiseOnce(eq("RELEASE_BLOCKED_" + paymentId), anyString(), anyMap());
    }

    @Test
    void gardeApparueEnReprise_apresRechercheVide_rendLeSequestre() throws Exception {
        // Reprise (essai 2) : la recherche Stripe vient de réussir sans rien trouver et le dernier
        // envoi est trop ancien pour être en vol ; la garde (colis annulé…) bloque : le séquestre
        // est rendu, le paiement redevient remboursable.
        StripeReleaseIntentEntity intent = reserved(2);
        intent.setCreateSentAt(settledSend());
        blockedBy = "colis CANCELLED";
        when(payments.revertUnpaidReleaseClaim(paymentId)).thenReturn(1);
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.BLOCKED);
            tr.verifyNoInteractions();
        }
        verify(payments).revertUnpaidReleaseClaim(paymentId);
        // Alerte levée APRÈS l'annulation réussie (et l'éventuelle précédente close par l'annulation).
        var order = org.mockito.Mockito.inOrder(payments, alerts);
        order.verify(payments).revertUnpaidReleaseClaim(paymentId);
        order.verify(alerts).resolveOpen("RELEASE_BLOCKED_" + paymentId);
        order.verify(alerts).raiseOnce(eq("RELEASE_BLOCKED_" + paymentId), anyString(), anyMap());
    }

    @Test
    void gardeApparue_envoiRecent_rechercheVide_neRendJamaisLeSequestre() throws Exception {
        // Cas 2 : un Transfer.create est parti il y a moins de SEND_SAFETY (détenteur au bail
        // expiré, réponse en route) : la recherche vide ne prouve rien, aucun retour en séquestre.
        StripeReleaseIntentEntity intent = reserved(2);
        blockedBy = "colis CANCELLED";
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.PENDING_RETRY);
            tr.verifyNoInteractions();
        }
        verify(intents, never()).markReverted(any(), any(), any());
        verify(payments, never()).revertUnpaidReleaseClaim(any());
        verify(alerts).raiseOnce(eq("RELEASE_BLOCKED_" + paymentId), anyString(), anyMap());
    }

    @Test
    void rechercheImpossible_sansEnvoiMaisEssaisMultiples_rendLeSequestre() throws Exception {
        // create_sent_at NULL : aucun Transfer n'a jamais été envoyé pour cette révision.
        StripeReleaseIntentEntity intent = reserved(1);
        intent.setAttempts(4);
        when(lookup.findExistingTransfer(any(), any(), any())).thenThrow(new ApiConnectionException("timeout"));
        assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.REVERTED);
    }

    @Test
    void bailReprisAvantLEnvoi_aucunTransfer() throws Exception {
        // Cas 1 : le bail a été repris (jeton changé) pendant la recherche ou la garde.
        StripeReleaseIntentEntity intent = reserved(1);
        when(intents.markCreateSent(any(), any(), any())).thenReturn(0);
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.NOT_OWNED);
            tr.verifyNoInteractions();
        }
        verify(intents, never()).markReverted(any(), any(), any());
    }

    @Test
    void envoiTraceSousLeJetonAvantTransferCreate() throws Exception {
        StripeReleaseIntentEntity intent = reserved(1);
        java.util.UUID token = intent.getLeaseToken();
        assertThat(token).isNotNull();
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            Transfer transfer = mock(Transfer.class);
            when(transfer.getId()).thenReturn("tr_1");
            tr.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class))).thenReturn(transfer);
            executor.execute(intent);
        }
        verify(intents).markCreateSent(intentId, token, now);
    }

    @Test
    void issueIncertaine_bailRepris_rienEcrit() throws Exception {
        // Détenteur au bail expiré : son issue n'est plus enregistrée (jeton refusé).
        StripeReleaseIntentEntity intent = reserved(1);
        when(intents.recordRetry(any(), any(), any(), any())).thenReturn(0);
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            tr.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new ApiConnectionException("timeout"));
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.NOT_OWNED);
        }
        verify(alerts, never()).raiseOnce(anyString(), anyString(), anyMap());
    }

    @Test
    void resume_jetonChangeApresLaPrise_neFaitRien() {
        StripeReleaseIntentEntity intent = reserved(2);
        org.mockito.Mockito.doReturn(1).when(intents).claimLease(any(), any(), any(), any());
        when(intents.findById(intentId)).thenReturn(Optional.of(intent));
        assertThat(executor.resume(intentId).result()).isEqualTo(StripeReleaseExecutor.Result.NOT_OWNED);
    }

    @Test
    void gardeApparue_annulationPerdue_aucuneAlerteBloquee() throws Exception {
        StripeReleaseIntentEntity intent = reserved(1);
        blockedBy = "litige bancaire ouvert";
        when(intents.markReverted(any(), any(), any())).thenReturn(0);
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.NOT_OWNED);
        }
        verify(alerts, never()).raiseOnce(eq("RELEASE_BLOCKED_" + paymentId), anyString(), anyMap());
    }

    @Test
    void gardeApparueAvantCaptureLegacy_aucuneCapture() throws Exception {
        StripeReleaseIntentEntity intent = legacyIntent();
        blockedBy = "colis CANCELLED";
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn("requires_capture");
        try (MockedStatic<PaymentIntent> st = mockStatic(PaymentIntent.class)) {
            st.when(() -> PaymentIntent.retrieve("pi_1")).thenReturn(pi);
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.BLOCKED);
        }
        verify(pi, never()).capture(any(PaymentIntentCaptureParams.class), any(RequestOptions.class));
    }

    @Test
    void refus4xxEnReprise_apresRechercheVide_rendLeSequestre() throws Exception {
        // Compte Connect rejeté après un premier essai en timeout : la recherche (réussie, vide)
        // prouve qu'aucun Transfer n'existe (dernier envoi trop ancien pour être en vol), le
        // refus 4xx de la création rend le séquestre.
        StripeReleaseIntentEntity intent = reserved(2);
        intent.setCreateSentAt(settledSend());
        when(payments.revertUnpaidReleaseClaim(paymentId)).thenReturn(1);
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            tr.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new InvalidRequestException("account rejected", null, "req", null, 400, null));
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.REVERTED);
        }
        verify(payments).revertUnpaidReleaseClaim(paymentId);
    }

    @Test
    void refus4xxEnReprise_envoiRecent_neRendJamaisLeSequestre() throws Exception {
        StripeReleaseIntentEntity intent = reserved(2);
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            tr.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new InvalidRequestException("account rejected", null, "req", null, 400, null));
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.PENDING_RETRY);
        }
        verify(payments, never()).revertUnpaidReleaseClaim(any());
        verify(alerts).raiseOnce(eq("RELEASE_PENDING_" + paymentId), anyString(), anyMap());
    }

    @Test
    void cleDIdempotenceEnCoursEnReprise_neRendJamaisLeSequestre() throws Exception {
        StripeReleaseIntentEntity intent = reserved(2);
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            tr.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new IdempotencyException("in progress", "req", null, 409));
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.PENDING_RETRY);
        }
        verify(payments, never()).revertUnpaidReleaseClaim(any());
        verify(intents, never()).markReverted(any(), any(), any());
    }

    @Test
    void refusLegacyEnReprise_neRendJamaisLeSequestre() throws Exception {
        // Capture legacy : pas de recherche de Transfer, un 4xx après un essai incertain laisse
        // le paiement réservé (alerte, décision admin).
        StripeReleaseIntentEntity intent = legacyIntent();
        intent.setAttempts(2);
        intent.setCreateSentAt(now);
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn("requires_capture");
        when(pi.capture(any(PaymentIntentCaptureParams.class), any(RequestOptions.class)))
                .thenThrow(new InvalidRequestException("bad", null, "req", null, 400, null));
        try (MockedStatic<PaymentIntent> st = mockStatic(PaymentIntent.class)) {
            st.when(() -> PaymentIntent.retrieve("pi_1")).thenReturn(pi);
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.PENDING_RETRY);
        }
        verify(payments, never()).revertUnpaidReleaseClaim(any());
        verify(alerts).raiseOnce(eq("RELEASE_PENDING_" + paymentId), anyString(), anyMap());
    }

    @Test
    void nouvelleReservationApresAnnulation_nouvelleRevisionDoncNouvelleCle() {
        StripeReleaseIntentEntity old = new StripeReleaseIntentEntity();
        old.setPaymentId(paymentId);
        old.setStatus(StripeReleaseIntentEntity.Status.CANCELLED);
        old.setRevision(1);
        when(intents.findByPaymentId(paymentId)).thenReturn(Optional.of(old));

        StripeReleaseIntentEntity intent = executor.reserve(transferSpec());

        assertThat(intent.getRevision()).isEqualTo(2);
        assertThat(StripeReleaseExecutor.transferKey(intent)).isEqualTo("transfer-" + paymentId + "-r2");
    }

    @Test
    void repriseEnPanneLocale_delaiCroissantEtAlerteAuSeuil() {
        StripeReleaseIntentEntity intent = reserved(StripeReleaseExecutor.ALERT_AFTER_ATTEMPTS);
        when(intents.findById(intentId)).thenReturn(Optional.of(intent));
        when(payments.findById(paymentId)).thenThrow(new IllegalStateException("db down"));

        StripeReleaseExecutor.Execution execution = executor.resume(intentId);

        assertThat(execution.result()).isEqualTo(StripeReleaseExecutor.Result.PENDING_RETRY);
        verify(intents).recordRetry(eq(intentId), any(), org.mockito.ArgumentMatchers.contains("db down"),
                eq(now.plus(StripeReleaseExecutor.backoff(StripeReleaseExecutor.ALERT_AFTER_ATTEMPTS))));
        verify(alerts).raiseOnce(eq("RELEASE_PENDING_" + paymentId), anyString(), anyMap());
    }

    // ── Destination charge legacy ────────────────────────────────────────────

    private StripeReleaseIntentEntity legacyIntent() {
        StripeReleaseIntentEntity intent = executor.reserve(new StripeReleaseExecutor.ReleaseSpec(paymentId,
                StripeReleaseIntentEntity.Kind.LEGACY_CAPTURE, null, null, null, null, "delivery",
                "ESCROW_RELEASED_LEGACY", bidId, travelerId, senderId));
        ReflectionTestUtils.setField(intent, "id", intentId);
        current = intent;
        return intent;
    }

    @Test
    void legacy_captureAvecCleStable() throws Exception {
        StripeReleaseIntentEntity intent = legacyIntent();
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn("requires_capture");
        try (MockedStatic<PaymentIntent> st = mockStatic(PaymentIntent.class)) {
            st.when(() -> PaymentIntent.retrieve("pi_1")).thenReturn(pi);
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.RELEASED);
        }
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(pi).capture(any(PaymentIntentCaptureParams.class), options.capture());
        assertThat(options.getValue().getIdempotencyKey()).isEqualTo("capture-" + paymentId);
        verify(payments, never()).recordStripeTransferId(any(), any());
        verify(audit).log(eq("PAYMENT"), eq(paymentId), eq("ESCROW_RELEASED_LEGACY"), eq(bidId), anyMap());
    }

    @Test
    void legacy_dejaCapture_finaliseSansRecapturer() throws Exception {
        StripeReleaseIntentEntity intent = legacyIntent();
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn("succeeded");
        try (MockedStatic<PaymentIntent> st = mockStatic(PaymentIntent.class)) {
            st.when(() -> PaymentIntent.retrieve("pi_1")).thenReturn(pi);
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.RELEASED);
        }
        verify(pi, never()).capture(any(PaymentIntentCaptureParams.class), any(RequestOptions.class));
    }

    @Test
    void legacy_autorisationAnnulee_rendLeSequestre() {
        StripeReleaseIntentEntity intent = legacyIntent();
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn("canceled");
        try (MockedStatic<PaymentIntent> st = mockStatic(PaymentIntent.class)) {
            st.when(() -> PaymentIntent.retrieve("pi_1")).thenReturn(pi);
            assertThat(executor.execute(intent).result()).isEqualTo(StripeReleaseExecutor.Result.REVERTED);
        }
        verify(payments).revertUnpaidReleaseClaim(paymentId);
    }

    // ── Reprise par le job ───────────────────────────────────────────────────

    @Test
    void resume_bailPris_rejoueALIdentique() throws Exception {
        StripeReleaseIntentEntity intent = reserved(2);
        when(intents.findById(intentId)).thenReturn(Optional.of(intent));
        when(lookup.findExistingTransfer(any(), any(), any())).thenReturn(Optional.of("tr_lost_response"));

        assertThat(executor.resume(intentId).result()).isEqualTo(StripeReleaseExecutor.Result.REALIGNED);
        verify(intents).claimLease(eq(intentId), eq(now), eq(now.plus(StripeReleaseExecutor.LEASE)), any());
    }

    @Test
    void resume_bailDetenuAilleurs_neFaitRien() {
        when(intents.claimLease(any(), any(), any(), any())).thenReturn(0);
        assertThat(executor.resume(intentId).result()).isEqualTo(StripeReleaseExecutor.Result.NOT_OWNED);
        verify(intents, never()).findById(any());
    }

    @Test
    void resume_reservationDisparue_neFaitRien() {
        when(intents.findById(intentId)).thenReturn(Optional.empty());
        assertThat(executor.resume(intentId).result()).isEqualTo(StripeReleaseExecutor.Result.NOT_OWNED);
    }

    // ── Annulation par un super-admin ────────────────────────────────────────

    private final UUID adminId = UUID.randomUUID();

    private StripeReleaseIntentEntity pendingForAdmin() {
        StripeReleaseIntentEntity intent = reserved(3);
        intent.setCreateSentAt(settledSend());
        when(intents.findByPaymentId(paymentId)).thenReturn(Optional.of(intent));
        when(intents.findById(intentId)).thenReturn(Optional.of(intent));
        return intent;
    }

    @Test
    void annulationAdmin_envoiRecent_refuseeSansRecherche() throws Exception {
        // Cas 3 : Transfer.create envoyé il y a moins de SEND_SAFETY, recherche vide sans valeur.
        StripeReleaseIntentEntity intent = pendingForAdmin();
        intent.setCreateSentAt(now.minusMinutes(2));
        StripeReleaseExecutor.Cancellation c = executor.cancelReservation(paymentId, adminId, "Vérification admin");
        assertThat(c.result()).isEqualTo(StripeReleaseExecutor.CancelResult.IN_PROGRESS);
        verify(lookup, never()).findExistingTransfer(any(), any(), any());
        verify(intents).releaseAdminLease(eq(intentId), eq(intent.getLeaseToken()), any(), anyString());
        verify(intents, never()).markCancelled(any(), any(), any());
        verify(payments, never()).revertUnpaidReleaseClaim(any());
    }

    @Test
    void annulationAdmin_aucunTransfer_rendLeSequestreEtAudite() throws Exception {
        pendingForAdmin();
        when(payments.revertUnpaidReleaseClaim(paymentId)).thenReturn(1);
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            StripeReleaseExecutor.Cancellation c = executor.cancelReservation(paymentId, adminId, "Compte Connect rejeté");
            assertThat(c.result()).isEqualTo(StripeReleaseExecutor.CancelResult.CANCELLED);
            tr.verifyNoInteractions();
        }
        verify(intents).markCancelled(eq(intentId), any(), org.mockito.ArgumentMatchers.contains("Compte Connect rejeté"));
        verify(payments).revertUnpaidReleaseClaim(paymentId);
        verify(audit).log(eq("PAYMENT"), eq(paymentId), eq("ESCROW_RELEASE_CANCELLED_BY_ADMIN"), eq(adminId),
                org.mockito.ArgumentMatchers.argThat(m -> "Compte Connect rejeté".equals(m.get("reason"))));
        verify(alerts).resolveOpen("RELEASE_PENDING_" + paymentId);
    }

    @Test
    void annulationAdmin_transferTrouve_realigneSansRendreLeSequestre() throws Exception {
        pendingForAdmin();
        when(lookup.findExistingTransfer(eq(paymentId), any(), any())).thenReturn(Optional.of("tr_found"));
        StripeReleaseExecutor.Cancellation c = executor.cancelReservation(paymentId, adminId, "Vérification admin");
        assertThat(c.result()).isEqualTo(StripeReleaseExecutor.CancelResult.REALIGNED);
        assertThat(c.transferId()).isEqualTo("tr_found");
        verify(payments).recordStripeTransferId(paymentId, "tr_found");
        verify(payments, never()).revertUnpaidReleaseClaim(any());
        verify(intents, never()).markCancelled(any(), any(), any());
    }

    @Test
    void annulationAdmin_rechercheImpossible_rienNeChange() throws Exception {
        pendingForAdmin();
        when(lookup.findExistingTransfer(any(), any(), any())).thenThrow(new ApiConnectionException("timeout"));
        StripeReleaseExecutor.Cancellation c = executor.cancelReservation(paymentId, adminId, "Vérification admin");
        assertThat(c.result()).isEqualTo(StripeReleaseExecutor.CancelResult.LOOKUP_FAILED);
        verify(intents).releaseAdminLease(eq(intentId), any(), any(), anyString());
        verify(payments, never()).revertUnpaidReleaseClaim(any());
        verify(intents, never()).markCancelled(any(), any(), any());
    }

    @Test
    void annulationAdmin_appelEnVol_refusee() {
        pendingForAdmin();
        when(intents.claimForAdmin(any(), any(), any(), any())).thenReturn(0);
        assertThat(executor.cancelReservation(paymentId, adminId, "Vérification admin").result())
                .isEqualTo(StripeReleaseExecutor.CancelResult.IN_PROGRESS);
        verify(payments, never()).revertUnpaidReleaseClaim(any());
    }

    @Test
    void annulationAdmin_rienDeReserve_ouLegacy() {
        assertThat(executor.cancelReservation(paymentId, adminId, "Vérification admin").result())
                .isEqualTo(StripeReleaseExecutor.CancelResult.NOT_PENDING);
        StripeReleaseIntentEntity legacy = legacyIntent();
        when(intents.findByPaymentId(paymentId)).thenReturn(Optional.of(legacy));
        assertThat(executor.cancelReservation(paymentId, adminId, "Vérification admin").result())
                .isEqualTo(StripeReleaseExecutor.CancelResult.UNSUPPORTED);
    }

    @Test
    void annulationAdmin_concurrenteDejaFaite_neRendRienDeuxFois() {
        pendingForAdmin();
        when(intents.markCancelled(any(), any(), any())).thenReturn(0);
        assertThat(executor.cancelReservation(paymentId, adminId, "Vérification admin").result())
                .isEqualTo(StripeReleaseExecutor.CancelResult.NOT_PENDING);
        verify(payments, never()).revertUnpaidReleaseClaim(any());
    }

    @Test
    void annulationAdmin_sousTransaction_refusee() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> executor.cancelReservation(paymentId, adminId, "x"))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    // ── Classement des erreurs, délais ───────────────────────────────────────

    @Test
    void classement_desErreursStripe() {
        assertThat(StripeReleaseExecutor.definitiveRefusal(
                new InvalidRequestException("x", null, "r", null, 400, null))).isTrue();
        assertThat(StripeReleaseExecutor.definitiveRefusal(
                new CardException("x", "r", null, null, null, null, 402, null))).isTrue();
        assertThat(StripeReleaseExecutor.definitiveRefusal(
                new AuthenticationException("x", "r", null, 401))).isTrue();
        assertThat(StripeReleaseExecutor.definitiveRefusal(
                new PermissionException("x", "r", null, 403))).isTrue();
        assertThat(StripeReleaseExecutor.definitiveRefusal(new ApiConnectionException("x"))).isFalse();
        assertThat(StripeReleaseExecutor.definitiveRefusal(new ApiException("x", "r", null, 500, null))).isFalse();
        assertThat(StripeReleaseExecutor.definitiveRefusal(
                new RateLimitException("x", null, "r", null, 429, null))).isFalse();
        assertThat(StripeReleaseExecutor.definitiveRefusal(new IdempotencyException("x", "r", null, 400))).isFalse();
    }

    @Test
    void delaiDeReprise_croitPuisPlafonne() {
        assertThat(StripeReleaseExecutor.backoff(0)).isEqualTo(Duration.ofMinutes(5));
        assertThat(StripeReleaseExecutor.backoff(1)).isEqualTo(Duration.ofMinutes(5));
        assertThat(StripeReleaseExecutor.backoff(2)).isEqualTo(Duration.ofMinutes(10));
        assertThat(StripeReleaseExecutor.backoff(4)).isEqualTo(Duration.ofMinutes(40));
        assertThat(StripeReleaseExecutor.backoff(10)).isEqualTo(Duration.ofHours(1));
    }
}
