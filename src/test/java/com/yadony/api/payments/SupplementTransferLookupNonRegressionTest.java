package com.yadony.api.payments;

import com.stripe.model.Transfer;
import com.stripe.model.TransferCollection;
import com.stripe.param.TransferListParams;
import com.yadony.api.payments.supplement.BidSupplementCardPayouts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Non-régression (plan §1.3-f) : le Transfer du supplément, versé au même voyageur, n'est jamais
 * pris pour le versement principal du colis par {@link StripeTransferLookup} (groupe
 * {@code supplement_<id>} et pas de métadonnée {@code payment_id}). Sinon le versement principal
 * serait déclaré déjà fait (REALIGNED) et le voyageur ne toucherait que le supplément.
 */
class SupplementTransferLookupNonRegressionTest {

    @Test
    void transferDuSupplement_invisiblePourLeVersementPrincipal() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID supplementId = UUID.randomUUID();
        Transfer supplementTransfer = new Transfer();
        supplementTransfer.setId("tr_supplement");
        supplementTransfer.setTransferGroup(BidSupplementCardPayouts.transferGroup(supplementId));
        supplementTransfer.setMetadata(Map.of("bid_supplement_id", supplementId.toString(),
                "source", "supplement-release"));
        supplementTransfer.setReversed(false);

        StripeTransferLookup lookup = spy(new StripeTransferLookup());
        // Stripe filtre par groupe : seul le groupe demandé revient ; par compte, tout revient.
        doAnswer(inv -> {
            TransferListParams params = inv.getArgument(0);
            boolean matches = params.getTransferGroup() == null
                    || params.getTransferGroup().equals(supplementTransfer.getTransferGroup());
            TransferCollection page = mock(TransferCollection.class);
            when(page.autoPagingIterable()).thenReturn(matches ? List.of(supplementTransfer) : List.of());
            return page;
        }).when(lookup).list(any());

        assertThat(lookup.findExistingTransfer(paymentId, "acct_traveler", null)).isEmpty();
    }
}
