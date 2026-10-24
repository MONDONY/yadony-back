package com.yadony.api.payments;

import com.stripe.model.Transfer;
import com.stripe.model.TransferCollection;
import com.stripe.param.TransferListParams;
import com.yadony.api.payments.amendment.BidAmendmentPayouts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Non-régression : le Transfer d'une modification de colis, versé au même voyageur avec le groupe et les métadonnées
 * réellement posés par {@link BidAmendmentPayouts}, n'est jamais pris par {@link StripeTransferLookup} pour le
 * versement principal du colis (recherche par groupe {@code payment_<id>}, puis par compte et {@code payment_id}).
 * Sinon le versement principal serait déclaré déjà fait et le voyageur ne toucherait que la modification.
 */
class AmendmentTransferLookupNonRegressionTest {

    @Test
    void transferDeLaModification_invisiblePourLeVersementPrincipal() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID amendmentId = UUID.randomUUID();
        Transfer amendmentTransfer = new Transfer();
        amendmentTransfer.setId("tr_amendment");
        amendmentTransfer.setTransferGroup(BidAmendmentPayouts.transferGroup(amendmentId));
        amendmentTransfer.setMetadata(BidAmendmentPayouts.transferMetadata(amendmentId, UUID.randomUUID()));
        amendmentTransfer.setDestination("acct_traveler");
        amendmentTransfer.setReversed(false);

        StripeTransferLookup lookup = spy(new StripeTransferLookup());
        // Stripe filtre par groupe : seul le groupe demandé revient ; par compte, tout revient.
        doAnswer(inv -> {
            TransferListParams params = inv.getArgument(0);
            boolean matches = params.getTransferGroup() == null
                    || params.getTransferGroup().equals(amendmentTransfer.getTransferGroup());
            TransferCollection page = mock(TransferCollection.class);
            when(page.autoPagingIterable()).thenReturn(matches ? List.of(amendmentTransfer) : List.of());
            return page;
        }).when(lookup).list(any());

        assertThat(lookup.findExistingTransfer(paymentId, "acct_traveler", null)).isEmpty();
    }
}
