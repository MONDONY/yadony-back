package com.yadony.api.payments.amendment;

import com.yadony.api.matching.dto.BidAmendmentResponse;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptRequest;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptResponse;
import com.yadony.api.payments.amendment.dto.AmendmentCreateResponse;
import com.yadony.api.payments.amendment.dto.CreateAmendmentRequest;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.supplement.BidSupplementRail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Création, acceptation et confirmation de commission : transaction du service, puis suite du rail hors transaction. */
class BidAmendmentPaymentFlowTest {

    private final BidAmendmentService service = mock(BidAmendmentService.class);
    private final BidAmendmentCashRail cashComponent = mock(BidAmendmentCashRail.class);
    private final BidAmendmentCardRail cardComponent = mock(BidAmendmentCardRail.class);
    private final BidAmendmentRailHandler cardRail = mock(BidAmendmentRailHandler.class);
    private final BidAmendmentProperties properties = new BidAmendmentProperties();
    @SuppressWarnings("unchecked")
    private final ObjectProvider<BidAmendmentRailHandler> handlers = mock(ObjectProvider.class);
    private final UUID bidId = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final CreateAmendmentRequest createRequest =
            new CreateAmendmentRequest(new BigDecimal("3"), null, PaymentMethod.STRIPE);
    private final AmendmentAcceptRequest acceptRequest = new AmendmentAcceptRequest(null, null);

    private BidAmendmentPaymentFlow flow;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        when(cardRail.rail()).thenReturn(BidSupplementRail.STRIPE);
        when(handlers.orderedStream()).thenAnswer(inv -> Stream.of(cardRail));
        flow = new BidAmendmentPaymentFlow(service, cashComponent, cardComponent, properties, handlers);
    }

    private BidAmendmentResponse view(String rail) {
        return new BidAmendmentResponse(id, "PROPOSED", rail, "EUR", BigDecimal.ONE, List.of(), BigDecimal.TEN, null,
                null, null, null, null, true, null, null);
    }

    @Test
    void creation_suiteDuRailApresLeCommit() {
        AmendmentCreateResponse created = AmendmentCreateResponse.of(view("STRIPE"));
        AmendmentCreateResponse withSheet = new AmendmentCreateResponse(view("STRIPE"), "pi_secret", List.of("card"),
                "pk");
        when(service.create(bidId, "uid", createRequest)).thenReturn(created);
        when(cardRail.afterCreateCommit(created)).thenReturn(withSheet);

        assertThat(flow.create(bidId, "uid", createRequest)).isSameAs(withSheet);
    }

    @Test
    void creation_railSansComposant_reponseDuServiceTelleQuelle() {
        AmendmentCreateResponse created = AmendmentCreateResponse.of(view("PAWAPAY"));
        when(service.create(bidId, "uid", createRequest)).thenReturn(created);

        assertThat(flow.create(bidId, "uid", createRequest)).isSameAs(created);
        verify(cardRail, never()).afterCreateCommit(any());
    }

    @Test
    void creation_interblocagePersistant_409PaymentBusy() {
        when(service.create(bidId, "uid", createRequest)).thenThrow(new CannotAcquireLockException("deadlock"));

        assertThatThrownBy(() -> flow.create(bidId, "uid", createRequest))
                .hasFieldOrPropertyWithValue("errorCode", "payment-busy");
        verify(service, times(3)).create(bidId, "uid", createRequest);
        verify(cardRail, never()).afterCreateCommit(any());
    }

    @Test
    void creation_interblocageUnique_rejoueDansUneTransactionNeuve() {
        AmendmentCreateResponse created = AmendmentCreateResponse.of(view("PAWAPAY"));
        when(service.create(bidId, "uid", createRequest))
                .thenThrow(new PessimisticLockingFailureException("deadlock")).thenReturn(created);

        assertThat(flow.create(bidId, "uid", createRequest)).isSameAs(created);
        verify(service, times(2)).create(bidId, "uid", createRequest);
    }

    @Test
    void acceptation_suiteDuRailApresLeCommit() {
        AmendmentAcceptResponse prepared = new AmendmentAcceptResponse(view("STRIPE"), null);
        AmendmentAcceptResponse captured = new AmendmentAcceptResponse(view("STRIPE"), null);
        when(service.accept(bidId, id, "uid", acceptRequest)).thenReturn(prepared);
        when(cardRail.afterAcceptCommit(prepared)).thenReturn(captured);

        assertThat(flow.accept(bidId, id, "uid", acceptRequest)).isSameAs(captured);
    }

    @Test
    void acceptation_interblocagePersistant_409PaymentBusy() {
        when(service.accept(bidId, id, "uid", acceptRequest)).thenThrow(new PessimisticLockingFailureException("x"));

        assertThatThrownBy(() -> flow.accept(bidId, id, "uid", acceptRequest))
                .hasFieldOrPropertyWithValue("errorCode", "payment-busy");
        verify(cardRail, never()).afterAcceptCommit(any());
    }

    @Test
    void confirmationDeLaCommission_delegueAuRailEspeces() {
        BidAmendmentResponse accepted = view("CASH");
        when(cashComponent.confirmCommission(bidId, id, "uid")).thenReturn(accepted);

        assertThat(flow.confirmCommission(bidId, id, "uid")).isSameAs(accepted);
    }

    @Test
    void confirmationDeLaCommission_interblocage_409PaymentBusy() {
        when(cashComponent.confirmCommission(bidId, id, "uid")).thenThrow(new CannotAcquireLockException("x"));

        assertThatThrownBy(() -> flow.confirmCommission(bidId, id, "uid"))
                .hasFieldOrPropertyWithValue("errorCode", "payment-busy");
    }

    @Test
    void confirmationDeLaCommission_drapeauEteint_422AmendmentDisabled() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> flow.confirmCommission(bidId, id, "uid"))
                .hasFieldOrPropertyWithValue("errorCode", "amendment-disabled");
        verifyNoInteractions(cashComponent);
    }

    @Test
    void confirmationDeLAutorisation_delegueAuRailCarte() {
        BidAmendmentResponse proposed = view("STRIPE");
        when(cardComponent.confirmAuthorization(bidId, id, "uid")).thenReturn(proposed);

        assertThat(flow.confirmAuthorization(bidId, id, "uid")).isSameAs(proposed);
    }

    @Test
    void confirmationDeLAutorisation_interblocage_409PaymentBusy() {
        when(cardComponent.confirmAuthorization(bidId, id, "uid")).thenThrow(new CannotAcquireLockException("x"));

        assertThatThrownBy(() -> flow.confirmAuthorization(bidId, id, "uid"))
                .hasFieldOrPropertyWithValue("errorCode", "payment-busy");
    }

    @Test
    void confirmationDeLAutorisation_drapeauEteint_422AmendmentDisabled() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> flow.confirmAuthorization(bidId, id, "uid"))
                .hasFieldOrPropertyWithValue("errorCode", "amendment-disabled");
        verifyNoInteractions(cardComponent);
    }
}
