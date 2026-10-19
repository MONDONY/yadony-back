package com.yadony.api.payments.supplement;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.supplement.BidSupplementCardLedger.RefundDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Remboursement mobile money du supplément : refund du dépôt du supplément, jamais après un versement. */
@ExtendWith(MockitoExtension.class)
class BidSupplementMobileMoneyRefundsTest {

    @Mock BidSupplementMobileMoneyLedger ledger;
    @Mock PawapayOperationService operations;
    @Mock PawapaySubmissionService submission;
    @Mock PawapayClient client;
    @Mock AdminAlertEscalator alerts;

    private final BidSupplementProperties properties = new BidSupplementProperties();
    private BidSupplementMobileMoneyRefunds refunds;
    private final UUID id = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private BidSupplementMobileMoneyLedger.Claim claim;
    private PawapayOperationEntity deposit;

    @BeforeEach
    void setUp() {
        refunds = new BidSupplementMobileMoneyRefunds(ledger, operations, submission, client, alerts, properties);
        claim = new BidSupplementMobileMoneyLedger.Claim(id, UUID.randomUUID(), senderId, UUID.randomUUID(),
                new BigDecimal("13000"), new BigDecimal("13700"), "XOF", BidSupplementStatus.REFUND_PENDING,
                LocalDateTime.now());
        deposit = op(PawapayOperationKind.DEPOSIT, PawapayOperationStatus.COMPLETED);
        lenient().when(ledger.beginRefund(id)).thenReturn(
                new BidSupplementMobileMoneyLedger.RefundClaim(RefundDecision.PROCEED, claim));
        lenient().when(operations.findLiveForSupplement(id, PawapayOperationKind.PAYOUT)).thenReturn(Optional.empty());
        lenient().when(operations.findLiveForSupplement(id, PawapayOperationKind.REFUND)).thenReturn(Optional.empty());
        lenient().when(operations.findLiveForSupplement(id, PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(deposit));
        lenient().when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", config(true)));
    }

    private PawapayOperationEntity op(PawapayOperationKind kind, PawapayOperationStatus status) {
        PawapayOperationEntity op = PawapayOperationEntity.forSupplement(UUID.randomUUID(), kind, senderId, id, null,
                new BigDecimal("13700"), "XOF", "ORANGE_SEN", "SN", "221770000001");
        op.setStatus(status);
        return op;
    }

    private static PawapayProviderConfig config(boolean refund) {
        PawapayProviderConfig.Limits open = new PawapayProviderConfig.Limits(null, null, null, null);
        return new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", open, open, refund ? open : null);
    }

    @Test
    void remboursement_duDepotDuSupplement_montantEntier() {
        PawapayOperationEntity refund = op(PawapayOperationKind.REFUND, PawapayOperationStatus.ACCEPTED);
        when(submission.submitSupplementRefund(id, senderId, deposit, new BigDecimal("13700"))).thenReturn(refund);

        assertThat(refunds.refund(id)).isTrue();

        verify(ledger).auditOnly(eq(id), eq("BID_SUPPLEMENT_REFUND_SUBMITTED"), anyMap());
        verify(ledger, never()).completeRefund(any(), any(), any());
    }

    @Test
    void remboursement_rienARendre_ouVersementDejaParti() {
        when(ledger.beginRefund(id)).thenReturn(new BidSupplementMobileMoneyLedger.RefundClaim(RefundDecision.NOTHING, null));
        assertThat(refunds.refund(id)).isFalse();

        when(ledger.beginRefund(id)).thenReturn(new BidSupplementMobileMoneyLedger.RefundClaim(RefundDecision.AFTER_PAYOUT, claim));
        assertThat(refunds.refund(id)).isFalse();
        verify(alerts).raiseOnce(eq("SUPP_REFUND_PAID_OUT_" + id), anyString(), anyMap());
        verifyNoInteractions(submission);
    }

    @Test
    void remboursement_versementPawapayExistant_bloqueEtAlerte() {
        when(operations.findLiveForSupplement(id, PawapayOperationKind.PAYOUT))
                .thenReturn(Optional.of(op(PawapayOperationKind.PAYOUT, PawapayOperationStatus.ACCEPTED)));
        assertThat(refunds.refund(id)).isFalse();
        verify(alerts).raiseOnce(eq("SUPP_MM_PAYOUT_EXISTS_" + id), anyString(), anyMap());
        verifyNoInteractions(submission);
    }

    @Test
    void remboursement_dejaSoumis_aboutiEnregistre_enCoursAttendu() {
        PawapayOperationEntity done = op(PawapayOperationKind.REFUND, PawapayOperationStatus.COMPLETED);
        when(operations.findLiveForSupplement(id, PawapayOperationKind.REFUND)).thenReturn(Optional.of(done));
        when(ledger.completeRefund(id, done.getId(), "reconcile")).thenReturn(true);
        assertThat(refunds.refund(id)).isTrue();

        when(operations.findLiveForSupplement(id, PawapayOperationKind.REFUND))
                .thenReturn(Optional.of(op(PawapayOperationKind.REFUND, PawapayOperationStatus.ACCEPTED)));
        assertThat(refunds.refund(id)).isFalse();
        verifyNoInteractions(submission);
    }

    @Test
    void remboursement_sansDepotAbouti_alerte() {
        when(operations.findLiveForSupplement(id, PawapayOperationKind.DEPOSIT))
                .thenReturn(Optional.of(op(PawapayOperationKind.DEPOSIT, PawapayOperationStatus.ACCEPTED)));
        assertThat(refunds.refund(id)).isFalse();
        verify(alerts).raiseOnce(eq("SUPP_MM_NO_DEPOSIT_" + id), anyString(), anyMap());
    }

    @Test
    void remboursement_operateurSansRemboursement_alerteV1() {
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", config(false)));
        assertThat(refunds.refund(id)).isFalse();
        verify(alerts).raiseOnce(eq("SUPP_MM_NO_REFUND_" + id), anyString(), anyMap());
        verifyNoInteractions(submission);
    }

    @Test
    void remboursement_configurationIllisible_tenteQuandMeme() {
        when(client.activeConfiguration()).thenThrow(new IllegalStateException("pawaPay muet"));
        when(submission.submitSupplementRefund(any(), any(), any(), any()))
                .thenReturn(op(PawapayOperationKind.REFUND, PawapayOperationStatus.ACCEPTED));
        assertThat(refunds.refund(id)).isTrue();
    }

    @Test
    void remboursement_refuseOuEnEchec_alerteEtFiletReessaie() {
        PawapayOperationEntity rejected = op(PawapayOperationKind.REFUND, PawapayOperationStatus.SUBMIT_REJECTED);
        rejected.setFailureCode("DEPOSIT_NOT_REFUNDABLE");
        when(submission.submitSupplementRefund(any(), any(), any(), any()))
                .thenReturn(rejected)
                .thenThrow(new IllegalStateException("boom"));

        assertThat(refunds.refund(id)).isFalse();
        assertThat(refunds.refund(id)).isFalse();

        verify(alerts, org.mockito.Mockito.times(2)).raiseOnce(eq("SUPP_REFUND_FAILED_" + id), anyString(), anyMap());
        verify(ledger, org.mockito.Mockito.times(2)).auditOnly(eq(id), eq("BID_SUPPLEMENT_REFUND_FAILED"), anyMap());
    }

    @Test
    void remboursement_reseauMuetApresCreation_laisseAuPoller() {
        when(operations.findLiveForSupplement(id, PawapayOperationKind.REFUND))
                .thenReturn(Optional.empty(), Optional.of(op(PawapayOperationKind.REFUND, PawapayOperationStatus.CREATED)));
        when(submission.submitSupplementRefund(any(), any(), any(), any())).thenThrow(new IllegalStateException("muet"));
        assertThat(refunds.refund(id)).isTrue();
        verify(alerts, never()).raiseOnce(eq("SUPP_REFUND_FAILED_" + id), anyString(), anyMap());
    }

    @Test
    void remboursement_apresTroisEchecs_plusDeSoumission_alerteManuelle() {
        when(operations.countDeadForSupplement(id, PawapayOperationKind.REFUND)).thenReturn(3L);

        assertThat(refunds.refund(id)).isFalse();

        verify(alerts).raiseOnce(eq("SUPP_MM_REFUND_MANUAL_" + id), anyString(), anyMap());
        verifyNoInteractions(submission);
    }

    @Test
    void remboursement_sousLePlafond_soumis() {
        when(operations.countDeadForSupplement(id, PawapayOperationKind.REFUND)).thenReturn(2L);
        when(submission.submitSupplementRefund(any(), any(), any(), any()))
                .thenReturn(op(PawapayOperationKind.REFUND, PawapayOperationStatus.ACCEPTED));
        assertThat(refunds.refund(id)).isTrue();
    }
}
