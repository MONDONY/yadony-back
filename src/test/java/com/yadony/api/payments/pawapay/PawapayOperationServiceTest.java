package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    void create_persistsCreatedWithAssignedId() {
        when(repository.existsByPaymentIdAndKindAndStatusIn(any(), any(), any())).thenReturn(false);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        PawapayOperationEntity created = service.create(PawapayOperationKind.DEPOSIT, UUID.randomUUID(), null,
                new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "+221 77 123 45 67");

        assertThat(created.getId()).isNotNull();
        assertThat(created.getStatus()).isEqualTo(PawapayOperationStatus.CREATED);
        assertThat(created.getMsisdn()).isEqualTo("221771234567");
    }

    @Test
    void markSubmitted_accepted_rejected_duplicate() {
        PawapayOperationEntity accepted = op(UUID.randomUUID(), PawapayOperationKind.DEPOSIT);
        PawapayOperationEntity rejected = op(UUID.randomUUID(), PawapayOperationKind.DEPOSIT);
        PawapayOperationEntity dup = op(UUID.randomUUID(), PawapayOperationKind.DEPOSIT);
        when(repository.findById(accepted.getId())).thenReturn(Optional.of(accepted));
        when(repository.findById(rejected.getId())).thenReturn(Optional.of(rejected));
        when(repository.findById(dup.getId())).thenReturn(Optional.of(dup));

        service.markSubmitted(accepted.getId(), PawapayInitiationResult.accepted());
        service.markSubmitted(rejected.getId(), new PawapayInitiationResult(
                PawapayInitiationResult.Outcome.REJECTED, "PROVIDER_TEMPORARILY_UNAVAILABLE", "down"));
        service.markSubmitted(dup.getId(), new PawapayInitiationResult(
                PawapayInitiationResult.Outcome.DUPLICATE_IGNORED, null, null));

        assertThat(accepted.getStatus()).isEqualTo(PawapayOperationStatus.ACCEPTED);
        assertThat(accepted.getSubmittedAt()).isNotNull();
        assertThat(rejected.getStatus()).isEqualTo(PawapayOperationStatus.SUBMIT_REJECTED);
        assertThat(rejected.getFailureCode()).isEqualTo("PROVIDER_TEMPORARILY_UNAVAILABLE");
        assertThat(rejected.getFinalizedAt()).isNotNull();
        assertThat(dup.getStatus()).as("DUPLICATE_IGNORED : le poller tranche").isEqualTo(PawapayOperationStatus.CREATED);
        assertThat(dup.getSubmittedAt()).isNotNull();
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
    void apply_failed_publishesFailedEvent_withCode() {
        PawapayOperationEntity o = op(UUID.randomUUID(), PawapayOperationKind.PAYOUT);
        when(repository.findById(o.getId())).thenReturn(Optional.of(o));
        when(repository.applyTransition(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        service.apply(o.getId(), PawapayOperationStatus.FAILED, "INSUFFICIENT_BALANCE", "no funds", null, null, "{}",
                PawapayOperationService.Source.POLL);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(PawapayOperationFailedEvent.class)
                .extracting(e -> ((PawapayOperationFailedEvent) e).failureCode()).isEqualTo("INSUFFICIENT_BALANCE");
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
}
