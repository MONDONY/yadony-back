package com.yadony.api.payments.supplement;

import com.yadony.api.matching.dto.BidSupplementResponse;
import com.yadony.api.payments.supplement.dto.SupplementAcceptResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Acceptation en deux temps : transaction du service, puis suite du rail hors transaction. */
class BidSupplementPaymentFlowTest {

    private final BidSupplementService service = mock(BidSupplementService.class);
    private final BidSupplementCardRail cardRail = mock(BidSupplementCardRail.class);
    private final BidSupplementRailHandler cashRail = mock(BidSupplementRailHandler.class);
    private final BidSupplementCashRail cashComponent = mock(BidSupplementCashRail.class);
    private final UUID bidId = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();

    private BidSupplementPaymentFlow flow() {
        when(cardRail.rail()).thenReturn(BidSupplementRail.STRIPE);
        when(cashRail.rail()).thenReturn(null);
        return new BidSupplementPaymentFlow(service, cardRail, cashComponent, List.of(cardRail, cashRail));
    }

    private static SupplementAcceptResponse prepared(String rail) {
        return new SupplementAcceptResponse(null, rail, null, null, BigDecimal.TEN, "EUR");
    }

    @Test
    void carte_suiteApresLeCommit() {
        BidSupplementPaymentFlow flow = flow();
        SupplementAcceptResponse first = prepared("STRIPE");
        SupplementAcceptResponse paid = new SupplementAcceptResponse(null, "STRIPE", "pi_secret", List.of("card"),
                BigDecimal.TEN, "EUR", "pk");
        when(service.accept(bidId, id, "uid")).thenReturn(first);
        when(cardRail.afterAcceptCommit(first)).thenReturn(paid);

        assertThat(flow.accept(bidId, id, "uid")).isSameAs(paid);
    }

    @Test
    void railSansComposantOuSansRail_reponseDuServiceTelleQuelle() {
        BidSupplementPaymentFlow flow = flow();
        SupplementAcceptResponse cash = prepared("CASH");
        when(service.accept(bidId, id, "uid")).thenReturn(cash, prepared(null));

        assertThat(flow.accept(bidId, id, "uid")).isSameAs(cash);
        assertThat(flow.accept(bidId, id, "uid").rail()).isNull();
        verify(cardRail, never()).afterAcceptCommit(cash);
    }

    @Test
    void interblocagePersistant_409PaymentBusy() {
        BidSupplementPaymentFlow flow = flow();
        when(service.accept(bidId, id, "uid")).thenThrow(new PessimisticLockingFailureException("deadlock"));

        assertThatThrownBy(() -> flow.accept(bidId, id, "uid"))
                .hasFieldOrPropertyWithValue("errorCode", "payment-busy");
        verify(service, times(3)).accept(bidId, id, "uid");
    }

    @Test
    void confirmationDuPaiement_delegueAuRailCarte() {
        BidSupplementPaymentFlow flow = flow();
        BidSupplementResponse view = mock(BidSupplementResponse.class);
        when(cardRail.confirmPayment(bidId, id, "uid")).thenReturn(view);
        assertThat(flow.confirmPayment(bidId, id, "uid")).isSameAs(view);
    }
}
