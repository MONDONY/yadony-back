package com.yadony.api.payments.pawapay;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.pawapay.dto.PawapayDepositRequest;
import com.yadony.api.payments.pawapay.dto.PawapayInitiationResult;
import com.yadony.api.payments.pawapay.dto.PawapayPayoutRequest;
import com.yadony.api.payments.pawapay.dto.PawapayRefundRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Opérations pawaPay d'un supplément de prix (plan §9 B3.1) : purpose {@code BID_SUPPLEMENT},
 * {@code bid_supplement_id}, {@code user_id} renseigné, {@code payment_id} toujours nul ; au plus une
 * opération vivante par (supplément, type).
 */
@ExtendWith(MockitoExtension.class)
class PawapaySubmissionServiceSupplementTest {

    @Mock PawapayOperationRepository repository;
    @Mock ApplicationEventPublisher events;
    @Mock PawapayClient client;
    @Mock PawapayOutOfFundsAlerter outOfFunds;

    private PawapaySubmissionService submission;
    private final UUID supplementId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        PawapayOperationService operations = new PawapayOperationService(repository, events);
        submission = new PawapaySubmissionService(operations, client, outOfFunds);
        lenient().when(repository.markSubmittedIfStillCreated(any(), any(), any(), any(), any(), any())).thenReturn(1);
    }

    private PawapayOperationEntity lastSaved;

    /** Enregistre l'opération et la rend relisible, comme la base. */
    private void persistAndReread() {
        when(repository.saveAndFlush(any())).thenAnswer(inv -> {
            lastSaved = inv.getArgument(0);
            when(repository.findById(lastSaved.getId())).thenReturn(Optional.of(lastSaved));
            return lastSaved;
        });
    }

    @Test
    void depot_purposeSupplement_expediteur_sansPaiementDuColis() {
        persistAndReread();
        when(client.initiateDeposit(any())).thenReturn(PawapayInitiationResult.accepted());

        PawapayOperationEntity op = submission.submitSupplementDeposit(supplementId, senderId, "+221 77 000 00 01",
                "ORANGE_SEN", "SN", new BigDecimal("13700"), "XOF", "supp-" + supplementId, null, null);

        assertThat(op.getKind()).isEqualTo(PawapayOperationKind.DEPOSIT);
        assertThat(op.getPurpose()).isEqualTo(PawapayOperationPurpose.BID_SUPPLEMENT);
        assertThat(op.getBidSupplementId()).isEqualTo(supplementId);
        assertThat(op.getUserId()).isEqualTo(senderId);
        assertThat(op.getPaymentId()).isNull();
        assertThat(op.getMsisdn()).isEqualTo("221770000001");
        ArgumentCaptor<PawapayDepositRequest> request = ArgumentCaptor.forClass(PawapayDepositRequest.class);
        verify(client).initiateDeposit(request.capture());
        assertThat(request.getValue().depositId()).isEqualTo(op.getId());
        assertThat(request.getValue().clientReferenceId()).isEqualTo("supp-" + supplementId);
        verify(repository).existsByBidSupplementIdAndKindAndStatusIn(supplementId, PawapayOperationKind.DEPOSIT,
                PawapayOperationStatus.LIVE_OR_DONE);
        verify(repository, never()).existsByPaymentIdAndKindAndStatusIn(any(), any(), any());
    }

    @Test
    void versement_purposeSupplement_voyageur() {
        UUID travelerId = UUID.randomUUID();
        when(client.initiatePayout(any())).thenReturn(PawapayInitiationResult.accepted());
        persistAndReread();

        PawapayOperationEntity op = submission.submitSupplementPayout(supplementId, travelerId, "221770000002",
                "WAVE_SEN", "SN", new BigDecimal("13000"), "XOF", "supp-" + supplementId);

        assertThat(op.getKind()).isEqualTo(PawapayOperationKind.PAYOUT);
        assertThat(op.getPurpose()).isEqualTo(PawapayOperationPurpose.BID_SUPPLEMENT);
        assertThat(op.getUserId()).isEqualTo(travelerId);
        assertThat(op.getPaymentId()).isNull();
        assertThat(op.getBidSupplementId()).isEqualTo(supplementId);
        ArgumentCaptor<PawapayPayoutRequest> request = ArgumentCaptor.forClass(PawapayPayoutRequest.class);
        verify(client).initiatePayout(request.capture());
        assertThat(request.getValue().amount()).isEqualByComparingTo("13000");
    }

    @Test
    void remboursement_lieAuDepotDuSupplement() {
        PawapayOperationEntity deposit = PawapayOperationEntity.forSupplement(UUID.randomUUID(),
                PawapayOperationKind.DEPOSIT, senderId, supplementId, null, new BigDecimal("13700"), "XOF",
                "ORANGE_SEN", "SN", "221770000001");
        when(client.initiateRefund(any())).thenReturn(PawapayInitiationResult.accepted());
        persistAndReread();

        PawapayOperationEntity op = submission.submitSupplementRefund(supplementId, senderId, deposit,
                deposit.getAmount());

        assertThat(op.getKind()).isEqualTo(PawapayOperationKind.REFUND);
        assertThat(op.getRelatedOperationId()).isEqualTo(deposit.getId());
        assertThat(op.getUserId()).isEqualTo(senderId);
        assertThat(op.getPaymentId()).isNull();
        assertThat(op.getBidSupplementId()).isEqualTo(supplementId);
        assertThat(op.getProvider()).isEqualTo("ORANGE_SEN");
        ArgumentCaptor<PawapayRefundRequest> request = ArgumentCaptor.forClass(PawapayRefundRequest.class);
        verify(client).initiateRefund(request.capture());
        assertThat(request.getValue().depositId()).isEqualTo(deposit.getId());
    }

    @Test
    void operationVivanteExistante_409_avantToutAppel() {
        when(repository.existsByBidSupplementIdAndKindAndStatusIn(eq(supplementId), eq(PawapayOperationKind.DEPOSIT),
                any())).thenReturn(true);
        assertThatThrownBy(() -> submission.submitSupplementDeposit(supplementId, senderId, "221770000001",
                "ORANGE_SEN", "SN", new BigDecimal("13700"), "XOF", "supp", null, null))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("mobile-money-operation-in-progress"));
        verify(repository, never()).saveAndFlush(any());
        verify(client, never()).initiateDeposit(any());
    }

    @Test
    void courseSurLIndexDuSupplement_409() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup",
                new SQLException("duplicate key value violates unique constraint \"uq_pawapay_ops_live_per_supplement\"")));
        assertThatThrownBy(() -> submission.submitSupplementPayout(supplementId, UUID.randomUUID(), "221770000002",
                "WAVE_SEN", "SN", new BigDecimal("13000"), "XOF", "supp"))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("mobile-money-operation-in-progress"));
        verify(client, never()).initiatePayout(any());
    }

    @Test
    void operationSansUtilisateurOuSansSupplement_refusee() {
        PawapayOperationService operations = new PawapayOperationService(repository, events);
        assertThatThrownBy(() -> operations.createForSupplement(PawapayOperationKind.DEPOSIT, null, supplementId, null,
                BigDecimal.ONE, "XOF", "ORANGE_SEN", "SN", "221770000001"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operations.createForSupplement(PawapayOperationKind.DEPOSIT, senderId, null, null,
                BigDecimal.ONE, "XOF", "ORANGE_SEN", "SN", "221770000001"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void lecturesDuSupplement() {
        PawapayOperationService operations = new PawapayOperationService(repository, events);
        operations.findLiveForSupplement(supplementId, PawapayOperationKind.PAYOUT);
        operations.findLatestForSupplement(supplementId, PawapayOperationKind.DEPOSIT);
        verify(repository).findFirstByBidSupplementIdAndKindAndStatusInOrderByCreatedAtDesc(supplementId,
                PawapayOperationKind.PAYOUT, PawapayOperationStatus.LIVE_OR_DONE);
        verify(repository).findFirstByBidSupplementIdAndKindOrderByCreatedAtDesc(supplementId,
                PawapayOperationKind.DEPOSIT);
    }
}
