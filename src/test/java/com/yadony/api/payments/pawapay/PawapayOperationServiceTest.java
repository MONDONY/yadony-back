package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.pawapay.dto.PawapayInitiationResult;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class PawapayOperationServiceTest {

    @Mock PawapayOperationRepository repository;
    @Mock ApplicationEventPublisher events;
    @InjectMocks PawapayOperationService service;

    private PawapayOperationEntity op(UUID paymentId, PawapayOperationKind kind) {
        return new PawapayOperationEntity(UUID.randomUUID(), kind, paymentId, null,
                new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
    }

    @Test
    void create_refusesWhenALiveOperationExists() {
        UUID paymentId = UUID.randomUUID();
        when(repository.existsByPaymentIdAndKindAndStatusIn(paymentId, PawapayOperationKind.DEPOSIT,
                PawapayOperationStatus.LIVE_OR_DONE)).thenReturn(true);

        assertThatThrownBy(() -> service.create(PawapayOperationKind.DEPOSIT, paymentId, null,
                new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567"))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-operation-in-progress");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_translatesUniqueIndexViolationInto409() {
        UUID paymentId = UUID.randomUUID();
        when(repository.existsByPaymentIdAndKindAndStatusIn(any(), any(), any())).thenReturn(false);
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_pawapay_ops_live_per_payment"));

        assertThatThrownBy(() -> service.create(PawapayOperationKind.PAYOUT, paymentId, null,
                new BigDecimal("13200"), "XOF", "ORANGE_SEN", "SN", "221771234567"))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-operation-in-progress");
    }

    @Test
    void create_rethrowsOtherIntegrityViolations_notTranslatedTo409() {
        // Revue ronde 1, point 2 : un catch trop large traduirait AUSSI une FK/NOT NULL/CHECK
        // sans rapport avec l'index unique en faux 409 "operation en cours", cause perdue.
        UUID paymentId = UUID.randomUUID();
        when(repository.existsByPaymentIdAndKindAndStatusIn(any(), any(), any())).thenReturn(false);
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "insert or update on table \"pawapay_operations\" violates foreign key constraint "
                        + "\"pawapay_operations_payment_id_fkey\""));

        assertThatThrownBy(() -> service.create(PawapayOperationKind.DEPOSIT, paymentId, null,
                new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void create_persistsCreatedWithAssignedId() {
        when(repository.existsByPaymentIdAndKindAndStatusIn(any(), any(), any())).thenReturn(false);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        PawapayOperationEntity created = service.create(PawapayOperationKind.DEPOSIT, UUID.randomUUID(), null,
                new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "+221 77 123 45 67");

        assertThat(created.getId()).isNotNull();
        assertThat(created.getStatus()).isEqualTo(PawapayOperationStatus.CREATED);
        assertThat(created.getMsisdn()).isEqualTo("221771234567");
    }

    // Revue ronde 1, point 1 (CRITIQUE) : markSubmitted ne fait plus de read-modify-write
    // d'entite (sans protection reelle face a un applyTransition concurrent, bulk JPQL qui
    // n'incremente jamais @Version) mais un UPDATE garde par WHERE status = CREATED. Les
    // 4 tests suivants verifient les arguments passes a ce garde-fou, plus son cas de defaite.

    @Test
    void markSubmitted_accepted_callsGuardedUpdate_withAcceptedStatus_noFailureDetails() {
        UUID id = UUID.randomUUID();
        when(repository.markSubmittedIfStillCreated(eq(id), eq(PawapayOperationStatus.ACCEPTED),
                isNull(), isNull(), isNull(), any())).thenReturn(1);

        service.markSubmitted(id, PawapayInitiationResult.accepted());

        verify(repository).markSubmittedIfStillCreated(eq(id), eq(PawapayOperationStatus.ACCEPTED),
                isNull(), isNull(), isNull(), any());
    }

    @Test
    void markSubmitted_rejected_callsGuardedUpdate_withFailureDetailsAndFinalizedAt() {
        UUID id = UUID.randomUUID();
        when(repository.markSubmittedIfStillCreated(eq(id), eq(PawapayOperationStatus.SUBMIT_REJECTED),
                eq("PROVIDER_TEMPORARILY_UNAVAILABLE"), eq("down"), any(), any())).thenReturn(1);

        service.markSubmitted(id, new PawapayInitiationResult(
                PawapayInitiationResult.Outcome.REJECTED, "PROVIDER_TEMPORARILY_UNAVAILABLE", "down"));

        verify(repository).markSubmittedIfStillCreated(eq(id), eq(PawapayOperationStatus.SUBMIT_REJECTED),
                eq("PROVIDER_TEMPORARILY_UNAVAILABLE"), eq("down"), any(), any());
    }

    @Test
    void markSubmitted_duplicateIgnored_callsGuardedUpdate_statusUnchangedAtCreated() {
        UUID id = UUID.randomUUID();
        when(repository.markSubmittedIfStillCreated(eq(id), eq(PawapayOperationStatus.CREATED),
                isNull(), isNull(), isNull(), any())).thenReturn(1);

        service.markSubmitted(id, new PawapayInitiationResult(PawapayInitiationResult.Outcome.DUPLICATE_IGNORED, null, null));

        verify(repository).markSubmittedIfStillCreated(eq(id), eq(PawapayOperationStatus.CREATED),
                isNull(), isNull(), isNull(), any());
    }

    @Test
    void markSubmitted_whenGuardLoses_doesNotThrow_justLogsAndReturns() {
        // La ligne n'est deja plus CREATED (callback deja passe devant) : 0 ligne touchee,
        // markSubmitted ne doit ni lever, ni tenter un autre acces au depot.
        UUID id = UUID.randomUUID();
        when(repository.markSubmittedIfStillCreated(any(), any(), any(), any(), any(), any())).thenReturn(0);

        assertThatCode(() -> service.markSubmitted(id, PawapayInitiationResult.accepted()))
                .doesNotThrowAnyException();
        verify(repository, never()).findById(any());
        verify(repository, never()).save(any());
    }

    @Test
    void apply_completed_publishesOnce_whenRowMoved() {
        PawapayOperationEntity o = op(UUID.randomUUID(), PawapayOperationKind.DEPOSIT);
        when(repository.findById(o.getId())).thenReturn(Optional.of(o));
        when(repository.applyTransition(eq(o.getId()), eq(PawapayOperationStatus.COMPLETED), any(), any(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(1).thenReturn(0);

        assertThat(service.apply(o.getId(), PawapayOperationStatus.COMPLETED, null, null, "ptx", null, "{}",
                PawapayOperationService.Source.CALLBACK)).isTrue();
        assertThat(service.apply(o.getId(), PawapayOperationStatus.COMPLETED, null, null, "ptx", null, "{}",
                PawapayOperationService.Source.POLL)).as("rejeu").isFalse();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(PawapayOperationCompletedEvent.class)
                .extracting(e -> ((PawapayOperationCompletedEvent) e).paymentId()).isEqualTo(o.getPaymentId());
    }

    @Test
    void apply_failed_publishesEvent_withValuesReloadedFromDb_notRawParams() {
        // Revue ronde 1, point 6 : applyTransition applique COALESCE(:failureCode,
        // o.failureCode) — un null ne rase rien. Si cet appel n'apporte pas de nouveau
        // code (poller sans failureReason cette fois), la base garde un code anterieur
        // different du parametre recu ici ; l'evenement DOIT refleter la base, pas le
        // parametre. On simule ce COALESCE avec un deuxieme findById renvoyant un etat
        // "apres transition" different du premier ("avant").
        UUID id = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PawapayOperationEntity beforeTransition = new PawapayOperationEntity(id, PawapayOperationKind.PAYOUT, paymentId,
                null, new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        PawapayOperationEntity afterTransition = new PawapayOperationEntity(id, PawapayOperationKind.PAYOUT, paymentId,
                null, new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        afterTransition.setFailureCode("INSUFFICIENT_BALANCE"); // conserve par COALESCE, pose par un etat anterieur
        afterTransition.setFailureMessage("no funds (code pose plus tot)");
        when(repository.findById(id)).thenReturn(Optional.of(beforeTransition)).thenReturn(Optional.of(afterTransition));
        when(repository.applyTransition(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        // Cet appel-ci n'apporte aucun failureCode/failureMessage (poller sans raison cette fois).
        service.apply(id, PawapayOperationStatus.FAILED, null, null, null, null, "{}",
                PawapayOperationService.Source.POLL);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(PawapayOperationFailedEvent.class)
                .satisfies(e -> {
                    PawapayOperationFailedEvent failed = (PawapayOperationFailedEvent) e;
                    assertThat(failed.failureCode()).as("code de la base, pas le parametre null recu").isEqualTo("INSUFFICIENT_BALANCE");
                    assertThat(failed.failureMessage()).isEqualTo("no funds (code pose plus tot)");
                });
    }

    @Test
    void apply_nonFinal_updatesWithoutEvent() {
        PawapayOperationEntity o = op(UUID.randomUUID(), PawapayOperationKind.DEPOSIT);
        when(repository.findById(o.getId())).thenReturn(Optional.of(o));
        when(repository.applyTransition(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        assertThat(service.apply(o.getId(), PawapayOperationStatus.PROCESSING, null, null, null,
                "https://wave/auth", "{}", PawapayOperationService.Source.CALLBACK)).isTrue();
        verify(events, never()).publishEvent(any());
    }

    @Test
    void apply_unknownOperation_isFalse() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThat(service.apply(id, PawapayOperationStatus.COMPLETED, null, null, null, null, "{}",
                PawapayOperationService.Source.CALLBACK)).isFalse();
        verify(repository, never()).applyTransition(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void apply_final_whenRowDisappearsRightAfterTransition_failsLoudly() {
        // Structurellement impossible (aucune suppression physique), mais jamais silencieux :
        // publier un événement d'échec aux champs nuls masquerait la disparition.
        PawapayOperationEntity o = op(UUID.randomUUID(), PawapayOperationKind.DEPOSIT);
        when(repository.findById(o.getId())).thenReturn(Optional.of(o)).thenReturn(Optional.empty());
        when(repository.applyTransition(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        assertThatThrownBy(() -> service.apply(o.getId(), PawapayOperationStatus.FAILED, "PAYMENT_NOT_APPROVED", null, null,
                null, "{}", PawapayOperationService.Source.POLL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(o.getId().toString());
        verify(events, never()).publishEvent(any());
    }

    // ── Lectures déléguées : findLive (vivante ou aboutie), findLatest (quel que soit l'état), get ──

    @Test
    void findLive_looksUpTheNewestOperation_amongLiveOrDoneOnly() {
        UUID paymentId = UUID.randomUUID();
        PawapayOperationEntity live = op(paymentId, PawapayOperationKind.PAYOUT);
        when(repository.findFirstByPaymentIdAndKindAndStatusInOrderByCreatedAtDesc(paymentId, PawapayOperationKind.PAYOUT,
                PawapayOperationStatus.LIVE_OR_DONE)).thenReturn(Optional.of(live));

        assertThat(service.findLive(paymentId, PawapayOperationKind.PAYOUT)).contains(live);
    }

    @Test
    void findLatest_looksUpTheNewestOperation_whateverItsStatus() {
        UUID paymentId = UUID.randomUUID();
        PawapayOperationEntity dead = op(paymentId, PawapayOperationKind.REFUND);
        dead.setStatus(PawapayOperationStatus.FAILED);
        when(repository.findFirstByPaymentIdAndKindOrderByCreatedAtDesc(paymentId, PawapayOperationKind.REFUND))
                .thenReturn(Optional.of(dead));

        assertThat(service.findLatest(paymentId, PawapayOperationKind.REFUND)).contains(dead);
    }

    @Test
    void get_returnsTheOperation_orFailsLoudlyWhenUnknown() {
        PawapayOperationEntity o = op(UUID.randomUUID(), PawapayOperationKind.DEPOSIT);
        when(repository.findById(o.getId())).thenReturn(Optional.of(o));
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(service.get(o.getId())).isSameAs(o);
        assertThatThrownBy(() -> service.get(unknown))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(unknown.toString());
    }
}
