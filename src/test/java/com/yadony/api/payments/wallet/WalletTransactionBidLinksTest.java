package com.yadony.api.payments.wallet;

import com.yadony.api.matching.BidRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** FLUTTER-QP : colis lié à chaque ligne de l'historique du solde, résolu en lot. */
@ExtendWith(MockitoExtension.class)
class WalletTransactionBidLinksTest {

    @Mock BidRepository bidRepository;
    @InjectMocks WalletTransactionBidLinks links;

    private static WalletTransactionEntity tx(WalletTransactionType type, UUID bidId, String paymentRef) {
        WalletTransactionEntity tx = new WalletTransactionEntity();
        ReflectionTestUtils.setField(tx, "id", UUID.randomUUID());
        tx.setType(type);
        tx.setBidId(bidId);
        tx.setPaymentRef(paymentRef);
        return tx;
    }

    private static BidRepository.BidThreadLink link(UUID id, UUID thread) {
        return new BidRepository.BidThreadLink() {
            @Override public UUID getId() { return id; }
            @Override public UUID getLinkedNegotiationThreadId() { return thread; }
        };
    }

    @Test
    void lignesPortantDejaLeurColis_aucuneRequete() {
        UUID bid = UUID.randomUUID();
        WalletTransactionEntity debit = tx(WalletTransactionType.COMMISSION_DEDUCTED, bid, null);
        WalletTransactionEntity topUp = tx(WalletTransactionType.TOP_UP, null, "pi_123");

        Map<UUID, UUID> result = links.resolve(List.of(debit, topUp));

        assertThat(result).containsExactly(Map.entry(debit.getId(), bid));
        verifyNoInteractions(bidRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void commissionDeNegociation_etRemboursementAncien_resolusEnUneRequete() {
        UUID thread = UUID.randomUUID();
        UUID negotiatedBid = UUID.randomUUID();
        UUID refundedBid = UUID.randomUUID();
        UUID vanishedBid = UUID.randomUUID();
        WalletTransactionEntity negoDebit = tx(WalletTransactionType.COMMISSION_DEDUCTED, null, thread.toString());
        WalletTransactionEntity negoDebitActive = tx(WalletTransactionType.COMMISSION_DEDUCTED, null, thread.toString());
        WalletTransactionEntity oldRefund = tx(WalletTransactionType.REFUND, null, "refund-" + refundedBid);
        WalletTransactionEntity orphanRefund = tx(WalletTransactionType.REFUND, null, "refund-" + vanishedBid);
        WalletTransactionEntity unmatchedThread = tx(WalletTransactionType.COMMISSION_DEDUCTED, null,
                UUID.randomUUID().toString());
        when(bidRepository.findBidThreadLinks(any(), any())).thenReturn(List.of(
                link(negotiatedBid, thread), link(refundedBid, null)));

        Map<UUID, UUID> result = links.resolve(List.of(negoDebit, negoDebitActive, oldRefund, orphanRefund,
                unmatchedThread));

        assertThat(result).containsOnly(
                Map.entry(negoDebit.getId(), negotiatedBid),
                Map.entry(negoDebitActive.getId(), negotiatedBid),
                Map.entry(oldRefund.getId(), refundedBid));
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Collection<UUID>> threads = ArgumentCaptor.forClass(Collection.class);
        verify(bidRepository, times(1)).findBidThreadLinks(ids.capture(), threads.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(refundedBid, vanishedBid);
        assertThat(threads.getValue()).hasSize(2).contains(thread);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listeVide_remplaceeParUneSentinelle() {
        UUID thread = UUID.randomUUID();
        when(bidRepository.findBidThreadLinks(any(), any())).thenReturn(List.of());

        links.resolve(List.of(tx(WalletTransactionType.COMMISSION_DEDUCTED, null, thread.toString())));

        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(bidRepository).findBidThreadLinks(ids.capture(), any());
        assertThat(ids.getValue()).containsExactly(new UUID(0L, 0L));
    }

    @Test
    void referencesIllisibles_ignorees() {
        Map<UUID, UUID> result = links.resolve(List.of(
                tx(WalletTransactionType.COMMISSION_DEDUCTED, null, null),
                tx(WalletTransactionType.COMMISSION_DEDUCTED, null, "pas-un-uuid"),
                tx(WalletTransactionType.COMMISSION_DEDUCTED, null, "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz"),
                tx(WalletTransactionType.REFUND, null, "refund-pas-un-uuid"),
                tx(WalletTransactionType.REFUND, null, "pi_123"),
                tx(WalletTransactionType.REFUND, null, null),
                tx(WalletTransactionType.TOP_UP, null, UUID.randomUUID().toString())));

        assertThat(result).isEmpty();
        verifyNoInteractions(bidRepository);
    }
}
