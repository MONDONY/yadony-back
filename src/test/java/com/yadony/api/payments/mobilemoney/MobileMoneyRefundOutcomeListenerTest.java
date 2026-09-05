package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MobileMoneyRefundOutcomeListenerTest {

    @Mock AdminAlertService adminAlert;
    @Mock AuditService audit;
    @InjectMocks MobileMoneyRefundOutcomeListener listener;

    @Test
    void completed_audits() {
        UUID paymentId = UUID.randomUUID();
        listener.onCompleted(new PawapayOperationCompletedEvent(UUID.randomUUID(), PawapayOperationKind.REFUND, paymentId));
        verify(audit).log(eq("PAYMENT"), eq(paymentId), eq("MM_REFUND_COMPLETED"), any(), any());
        verify(adminAlert, never()).raise(any(), any(), any());
    }

    @Test
    void failed_alerts() {
        UUID paymentId = UUID.randomUUID();
        listener.onFailed(new PawapayOperationFailedEvent(UUID.randomUUID(), PawapayOperationKind.REFUND, paymentId, "X", "y"));
        verify(adminAlert).raise(eq("PAWAPAY_REFUND_FAILED"), any(), any());
        verify(audit).log(eq("PAYMENT"), eq(paymentId), eq("MM_REFUND_FAILED"), any(), any());
    }

    @Test
    void otherKinds_ignored() {
        listener.onCompleted(new PawapayOperationCompletedEvent(UUID.randomUUID(), PawapayOperationKind.DEPOSIT, UUID.randomUUID()));
        verify(audit, never()).log(any(), any(), any(), any(), any());
    }

    /**
     * Invariant projet (absent du Step 1 du brief, ajouté ici) : toute valeur non authentifiée
     * (pawaPay) journalisée est tronquée à 64 caractères — même garde que
     * {@code MobileMoneyPayoutOutcomeListener#failedPayout_truncatesLongFailureMessageTo64Chars}
     * (tâche 16, jumeau payout). Sans ce test, la réintroduction d'un
     * {@code String.valueOf(event.failureMessage())} non borné passerait inaperçue.
     */
    @Test
    void failed_truncatesLongFailureCodeAndMessageTo64Chars() {
        UUID paymentId = UUID.randomUUID();
        String longMessage = "x".repeat(200);
        String longCode = "y".repeat(200);
        listener.onFailed(new PawapayOperationFailedEvent(UUID.randomUUID(), PawapayOperationKind.REFUND, paymentId, longCode, longMessage));

        ArgumentCaptor<Map<String, Object>> auditPayload = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("PAYMENT"), eq(paymentId), eq("MM_REFUND_FAILED"), any(), auditPayload.capture());
        assertThat(((String) auditPayload.getValue().get("failureCode")).length()).isEqualTo(64);

        ArgumentCaptor<Map<String, Object>> alertContext = ArgumentCaptor.forClass(Map.class);
        verify(adminAlert).raise(eq("PAWAPAY_REFUND_FAILED"), any(), alertContext.capture());
        assertThat(((String) alertContext.getValue().get("failureCode")).length()).isEqualTo(64);
        assertThat(((String) alertContext.getValue().get("failureMessage")).length()).isEqualTo(64);
    }
}
