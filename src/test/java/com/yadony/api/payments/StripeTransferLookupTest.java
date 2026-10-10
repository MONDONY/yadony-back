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

    private static TransferCollection collection(List<Transfer> transfers) {
        TransferCollection c = mock(TransferCollection.class);
        when(c.autoPagingIterable()).thenReturn(transfers);
        return c;
    }

    /** 1er appel : recherche par groupe ; 2e : repli par compte de destination. */
    private StripeTransferLookup lookup(List<Transfer> byGroup, List<Transfer> byDestination,
                                        ArgumentCaptor<TransferListParams> captor) throws Exception {
        StripeTransferLookup lookup = spy(new StripeTransferLookup());
        doReturn(collection(byGroup), collection(byDestination)).when(lookup).list(captor.capture());
        return lookup;
    }

    @Test
    void trouveParGroupe_independammentDuCompte() throws Exception {
        ArgumentCaptor<TransferListParams> captor = ArgumentCaptor.forClass(TransferListParams.class);
        StripeTransferLookup lookup = lookup(List.of(transfer("tr_reversed", null, true), transfer("tr_group", null, false)),
                List.of(), captor);

        assertThat(lookup.findExistingTransfer(paymentId, "acct_new", null)).contains("tr_group");
        assertThat(captor.getAllValues()).hasSize(1);
        assertThat(captor.getValue().getTransferGroup()).isEqualTo("payment_" + paymentId);
    }

    @Test
    void ancienTransferSansGroupe_trouveParCompteEtMetadonnee() throws Exception {
        ArgumentCaptor<TransferListParams> captor = ArgumentCaptor.forClass(TransferListParams.class);
        LocalDateTime created = LocalDateTime.of(2026, 10, 1, 12, 0);
        StripeTransferLookup lookup = lookup(List.of(), List.of(
                transfer("tr_other", UUID.randomUUID().toString(), false),
                transfer("tr_nometa", null, false),
                transfer("tr_mine", paymentId.toString(), false)), captor);

        assertThat(lookup.findExistingTransfer(paymentId, "acct_t", created)).contains("tr_mine");

        TransferListParams params = captor.getAllValues().get(1);
        assertThat(params.getDestination()).isEqualTo("acct_t");
        assertThat(params.getLimit()).isEqualTo(100L);
        TransferListParams.Created range = (TransferListParams.Created) params.getCreated();
        assertThat(range.getGte()).isEqualTo(created.toEpochSecond(ZoneOffset.UTC)
                - StripeTransferLookup.CREATED_MARGIN_SECONDS);
    }

    @Test
    void transferEntierementAnnule_neComptePas_sansBorneDeDate() throws Exception {
        ArgumentCaptor<TransferListParams> captor = ArgumentCaptor.forClass(TransferListParams.class);
        StripeTransferLookup lookup = lookup(List.of(), List.of(transfer("tr_reversed", paymentId.toString(), true)), captor);

        assertThat(lookup.findExistingTransfer(paymentId, "acct_t", null)).isEmpty();
        assertThat(captor.getAllValues().get(1).getCreated()).isNull();
    }

    @Test
    void sansCompte_seuleLaRechercheParGroupe_sansPaiement_aucunAppel() throws Exception {
        ArgumentCaptor<TransferListParams> captor = ArgumentCaptor.forClass(TransferListParams.class);
        StripeTransferLookup lookup = lookup(List.of(), List.of(), captor);

        assertThat(lookup.findExistingTransfer(paymentId, " ", null)).isEmpty();
        assertThat(lookup.findExistingTransfer(paymentId, null, null)).isEmpty();
        verify(lookup, times(2)).list(any());
        assertThat(lookup.findExistingTransfer(null, "acct_t", null)).isEmpty();
        verify(lookup, times(2)).list(any());
    }
}
