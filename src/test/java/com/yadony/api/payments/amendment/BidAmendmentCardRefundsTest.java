package com.yadony.api.payments.amendment;

import com.yadony.api.admin.AdminAlertEscalator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Remboursement carte d'une modification : cas où le net est déjà parti chez le voyageur. */
@DisplayName("BidAmendmentCardRefunds")
class BidAmendmentCardRefundsTest {

    private final StripeAmendmentGateway gateway = mock(StripeAmendmentGateway.class);
    private final BidAmendmentCardLedger ledger = mock(BidAmendmentCardLedger.class);
    private final AdminAlertEscalator alerts = mock(AdminAlertEscalator.class);
    private final BidAmendmentCardRefunds refunds = new BidAmendmentCardRefunds(gateway, ledger, alerts);

    @Test
    @DisplayName("déjà versée : alerte AMEND_REFUND_PAID_OUT_, aucun Refund, rien n'est enregistré")
    void afterPayout_alertsWithoutStripe() {
        UUID id = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        when(ledger.beginRefund(id)).thenReturn(new BidAmendmentCardLedger.RefundClaim(
                BidAmendmentCardLedger.RefundDecision.AFTER_PAYOUT, id, bidId, "pi_1", null));

        assertThat(refunds.refund(id)).isFalse();

        verify(alerts).raiseOnce(eq("AMEND_REFUND_PAID_OUT_" + id), anyString(), anyMap());
        verifyNoInteractions(gateway);
        verify(ledger, never()).refundSubmitted(any(), any());
        verify(ledger, never()).auditOnly(any(), any(), any());
    }
}
