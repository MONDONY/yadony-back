package com.yadony.api.payments.supplement;

import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRefundRequestedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Suites pawaPay du supplément : versement après le principal mobile money, remboursement demandé. */
@ExtendWith(MockitoExtension.class)
class BidSupplementMobileMoneyListenerTest {

    @Mock BidSupplementMobileMoneyPayouts payouts;
    @Mock BidSupplementMobileMoneyRefunds refunds;
    @InjectMocks BidSupplementMobileMoneyListener listener;

    private final UUID bidId = UUID.randomUUID();

    @Test
    void versementPrincipalMobileMoneyConfirme_supplementVerse() {
        listener.onPaymentReleased(new PaymentReleasedEvent(bidId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("50000"), "XOF", true));
        verify(payouts).payoutForBid(bidId);
    }

    @Test
    void versementPrincipalCarte_ignore() {
        listener.onPaymentReleased(PaymentReleasedEvent.card(bidId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("50"), new BigDecimal("5"), "EUR"));
        verifyNoInteractions(payouts);
    }

    @Test
    void echecDuVersement_avale_leFiletReprend() {
        doThrow(new IllegalStateException("boom")).when(payouts).payoutForBid(bidId);
        assertThatCode(() -> listener.onPaymentReleased(new PaymentReleasedEvent(bidId, UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("50000"), "XOF", true))).doesNotThrowAnyException();
    }

    @Test
    void remboursementDemande_railMobileMoneySeulement() {
        UUID id = UUID.randomUUID();
        listener.onRefundRequested(new BidSupplementRefundRequestedEvent(id, bidId, BidSupplementRail.STRIPE,
                BidSupplementStatus.REFUND_PENDING, "PARCEL_REFUSED"));
        verifyNoInteractions(refunds);
        listener.onRefundRequested(new BidSupplementRefundRequestedEvent(id, bidId, BidSupplementRail.PAWAPAY,
                BidSupplementStatus.REFUND_PENDING, "PARCEL_REFUSED"));
        verify(refunds).refund(id);
        doThrow(new IllegalStateException("boom")).when(refunds).refund(id);
        assertThatCode(() -> listener.onRefundRequested(new BidSupplementRefundRequestedEvent(id, bidId,
                BidSupplementRail.PAWAPAY, BidSupplementStatus.REFUND_PENDING, "PARCEL_REFUSED")))
                .doesNotThrowAnyException();
    }
}
