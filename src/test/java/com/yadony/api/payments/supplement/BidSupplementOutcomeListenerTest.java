package com.yadony.api.payments.supplement;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationPurpose;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Issue des opérations pawaPay d'un supplément (plan §9 B3.2) : dépôt, versement, remboursement. */
@ExtendWith(MockitoExtension.class)
class BidSupplementOutcomeListenerTest {

    @Mock PawapayOperationService operations;
    @Mock BidSupplementMobileMoneyLedger ledger;
    @Mock AdminAlertEscalator alerts;
    @InjectMocks BidSupplementOutcomeListener listener;

    private final UUID supplementId = UUID.randomUUID();

    private PawapayOperationEntity op(PawapayOperationKind kind) {
        PawapayOperationEntity op = PawapayOperationEntity.forSupplement(UUID.randomUUID(), kind, UUID.randomUUID(),
                supplementId, null, new BigDecimal("13700"), "XOF", "ORANGE_SEN", "SN", "221770000001");
        when(operations.get(op.getId())).thenReturn(op);
        return op;
    }

    private static PawapayOperationCompletedEvent completed(PawapayOperationEntity op) {
        return new PawapayOperationCompletedEvent(op.getId(), op.getKind(), PawapayOperationPurpose.BID_SUPPLEMENT,
                null, op.getUserId());
    }

    private static PawapayOperationFailedEvent failed(PawapayOperationEntity op) {
        return new PawapayOperationFailedEvent(op.getId(), op.getKind(), PawapayOperationPurpose.BID_SUPPLEMENT, null,
                op.getUserId(), "PAYER_NOT_FOUND", "non");
    }

    @Test
    void depotAbouti_supplementPaye() {
        PawapayOperationEntity op = op(PawapayOperationKind.DEPOSIT);
        listener.onCompleted(completed(op));
        verify(ledger).markPaid(supplementId, op.getId(), "pawapay");
    }

    @Test
    void depotEchoue_resteEnAttente_auditSeulement() {
        PawapayOperationEntity op = op(PawapayOperationKind.DEPOSIT);
        listener.onFailed(failed(op));
        verify(ledger).auditOnly(eq(supplementId), eq("BID_SUPPLEMENT_MM_DEPOSIT_FAILED"), anyMap());
        verify(ledger, never()).markPaid(any(), any(), any());
        verifyNoInteractions(alerts);
    }

    @Test
    void versementAbouti_supplementVerse() {
        PawapayOperationEntity op = op(PawapayOperationKind.PAYOUT);
        listener.onCompleted(completed(op));
        verify(ledger).completePayout(supplementId, op.getId());
    }

    @Test
    void versementEchoue_retourAPayeEtAlerte() {
        PawapayOperationEntity op = op(PawapayOperationKind.PAYOUT);
        listener.onFailed(failed(op));
        verify(ledger).revertPayout(supplementId, "PAYER_NOT_FOUND");
        verify(alerts).raiseOnce(eq("SUPP_MM_PAYOUT_FAILED_" + supplementId), anyString(), anyMap());
    }

    @Test
    void remboursementAbouti_supplementRembourse() {
        PawapayOperationEntity op = op(PawapayOperationKind.REFUND);
        listener.onCompleted(completed(op));
        verify(ledger).completeRefund(supplementId, op.getId(), "pawapay");
    }

    @Test
    void remboursementEchoue_alerteEtFiletReessaie() {
        PawapayOperationEntity op = op(PawapayOperationKind.REFUND);
        listener.onFailed(failed(op));
        verify(ledger).auditOnly(eq(supplementId), eq("BID_SUPPLEMENT_REFUND_FAILED"), anyMap());
        verify(alerts).raiseOnce(eq("SUPP_REFUND_FAILED_" + supplementId), anyString(), anyMap());
        verify(ledger, never()).completeRefund(any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = PawapayOperationPurpose.class, names = "BID_SUPPLEMENT", mode = EnumSource.Mode.EXCLUDE)
    void autresPurposes_ignores(PawapayOperationPurpose purpose) {
        UUID opId = UUID.randomUUID();
        listener.onCompleted(new PawapayOperationCompletedEvent(opId, PawapayOperationKind.DEPOSIT, purpose,
                UUID.randomUUID(), null));
        listener.onFailed(new PawapayOperationFailedEvent(opId, PawapayOperationKind.PAYOUT, purpose,
                UUID.randomUUID(), null, "X", "Y"));
        verifyNoInteractions(operations, ledger, alerts);
    }

    @Test
    void operationSansSupplement_ignoree() {
        PawapayOperationEntity orphan = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT,
                PawapayOperationPurpose.BID_SUPPLEMENT, UUID.randomUUID(), null, null, new BigDecimal("1"), "XOF",
                "ORANGE_SEN", "SN", "221770000001");
        when(operations.get(orphan.getId())).thenReturn(orphan);
        listener.onCompleted(completed(orphan));
        listener.onFailed(failed(orphan));
        verifyNoInteractions(ledger, alerts);
    }
}
