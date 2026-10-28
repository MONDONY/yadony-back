package com.yadony.api.payments.pawapay;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.pawapay.events.PawapayLateCompletionEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PawapayLateCompletionAlerterTest {

    private final AdminAlertEscalator escalator = mock(AdminAlertEscalator.class);
    private final PawapayLateCompletionAlerter alerter = new PawapayLateCompletionAlerter(escalator);

    private PawapayLateCompletionEvent event(PawapayOperationKind kind, UUID operationId) {
        return new PawapayLateCompletionEvent(operationId, kind, PawapayOperationPurpose.WALLET_TOPUP,
                PawapayOperationStatus.FAILED, null, UUID.randomUUID(), new BigDecimal("10000"), "XOF");
    }

    @Test
    void depot_alerteCritiqueUniqueParOperation() {
        UUID operationId = UUID.randomUUID();

        alerter.onLateCompletion(event(PawapayOperationKind.DEPOSIT, operationId));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(escalator).raiseOnce(eq("pawapay-late-completed-" + operationId), eq("CRITICAL"),
                detail.capture(), context.capture());
        assertThat(detail.getValue()).contains("dépôt");
        assertThat(context.getValue()).containsEntry("operationId", operationId.toString())
                .containsEntry("previousStatus", "FAILED").containsEntry("amount", "10000");
        assertThat(("pawapay-late-completed-" + operationId).length())
                .isLessThanOrEqualTo(AdminAlertEscalator.TYPE_MAX_LENGTH);
    }

    @Test
    void versement_signaleLeRisqueDeDoubleVersement() {
        UUID operationId = UUID.randomUUID();

        alerter.onLateCompletion(event(PawapayOperationKind.REFUND, operationId));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(escalator).raiseOnce(eq("pawapay-late-completed-" + operationId), eq("CRITICAL"),
                detail.capture(), org.mockito.ArgumentMatchers.anyMap());
        assertThat(detail.getValue()).contains("seconde fois");
    }
}
