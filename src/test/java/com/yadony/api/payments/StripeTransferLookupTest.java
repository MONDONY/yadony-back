package com.yadony.api.payments;

import com.stripe.model.Transfer;
import com.stripe.model.TransferCollection;
import com.stripe.param.TransferListParams;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StripeTransferLookupTest {

    private final UUID paymentId = UUID.randomUUID();

    private static Transfer transfer(String id, String paymentId, Boolean reversed) {
        Transfer t = new Transfer();
        t.setId(id);
        t.setMetadata(paymentId == null ? null : Map.of("payment_id", paymentId));
        t.setReversed(reversed);
        return t;
    }

    private StripeTransferLookup lookupReturning(List<Transfer> transfers, ArgumentCaptor<TransferListParams> captor)
            throws Exception {
        StripeTransferLookup lookup = spy(new StripeTransferLookup());
        TransferCollection collection = mock(TransferCollection.class);
        when(collection.autoPagingIterable()).thenReturn(transfers);
        doReturn(collection).when(lookup).list(captor != null ? captor.capture() : any());
        return lookup;
    }

    @Test
    void trouveLeTransferDuPaiementParSaMetadonnee() throws Exception {
        ArgumentCaptor<TransferListParams> captor = ArgumentCaptor.forClass(TransferListParams.class);
        LocalDateTime created = LocalDateTime.of(2026, 10, 1, 12, 0);
        StripeTransferLookup lookup = lookupReturning(List.of(
                transfer("tr_other", UUID.randomUUID().toString(), false),
                transfer("tr_nometa", null, false),
                transfer("tr_mine", paymentId.toString(), false)), captor);

        assertThat(lookup.findExistingTransfer(paymentId, "acct_t", created)).contains("tr_mine");

        TransferListParams params = captor.getValue();
        assertThat(params.getDestination()).isEqualTo("acct_t");
        assertThat(params.getLimit()).isEqualTo(100L);
        TransferListParams.Created range = (TransferListParams.Created) params.getCreated();
        assertThat(range.getGte()).isEqualTo(created.toEpochSecond(ZoneOffset.UTC)
                - StripeTransferLookup.CREATED_MARGIN_SECONDS);
    }

    @Test
    void transferEntierementAnnule_neComptePas() throws Exception {
        StripeTransferLookup lookup = lookupReturning(List.of(
                transfer("tr_reversed", paymentId.toString(), true)), null);

        assertThat(lookup.findExistingTransfer(paymentId, "acct_t", null)).isEmpty();
    }

    @Test
    void sansBorneDeDate_aucunFiltreCreated() throws Exception {
        ArgumentCaptor<TransferListParams> captor = ArgumentCaptor.forClass(TransferListParams.class);
        StripeTransferLookup lookup = lookupReturning(List.of(), captor);

        assertThat(lookup.findExistingTransfer(paymentId, "acct_t", null)).isEmpty();
        assertThat(captor.getValue().getCreated()).isNull();
    }

    @Test
    void sansCompteDeDestinationOuPaiement_aucunAppelStripe() throws Exception {
        StripeTransferLookup lookup = spy(new StripeTransferLookup());

        assertThat(lookup.findExistingTransfer(paymentId, null, null)).isEqualTo(Optional.empty());
        assertThat(lookup.findExistingTransfer(paymentId, " ", null)).isEmpty();
        assertThat(lookup.findExistingTransfer(null, "acct_t", null)).isEmpty();
        verify(lookup, never()).list(any());
    }
}
