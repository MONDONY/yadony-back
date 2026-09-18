package com.yadony.api.payments.wallet;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WalletRefundIssueRecoverySchedulerTest {

    private final WalletRefundRequestItemRepository itemRepository = mock(WalletRefundRequestItemRepository.class);
    private final WalletSelfRefundService service = mock(WalletSelfRefundService.class);
    private final WalletRefundIssueRecoveryScheduler scheduler =
            new WalletRefundIssueRecoveryScheduler(itemRepository, service);

    @Test
    void recoverUnissuedItems_neRepritQueLesItemsCreesIlYAPlusDeDeuxMinutes() {
        when(itemRepository.findRequestIdsWithUnissuedItems(any(), any(), any(), any())).thenReturn(List.of());
        Instant before = Instant.now();

        scheduler.recoverUnissuedItems();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(itemRepository).findRequestIdsWithUnissuedItems(
                eq(List.of(WalletRefundChannel.AUTOMATIC_STRIPE, WalletRefundChannel.AUTOMATIC_PAWAPAY)),
                eq(WalletRefundRequestStatus.PROCESSING), eq(WalletRefundItemStatus.PENDING), cutoff.capture());
        assertThat(cutoff.getValue()).isBeforeOrEqualTo(before.minus(Duration.ofMinutes(2)).plusSeconds(1));
        assertThat(cutoff.getValue()).isAfter(before.minus(Duration.ofMinutes(3)));
        verifyNoInteractions(service);
    }

    @Test
    void recoverItemsCreatedBefore_emetChaqueDemandeMemeSiUneEchoue() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Instant cutoff = Instant.now();
        when(itemRepository.findRequestIdsWithUnissuedItems(
                List.of(WalletRefundChannel.AUTOMATIC_STRIPE, WalletRefundChannel.AUTOMATIC_PAWAPAY),
                WalletRefundRequestStatus.PROCESSING, WalletRefundItemStatus.PENDING, cutoff))
                .thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("verrou")).when(service).issuePendingItems(first);

        scheduler.recoverItemsCreatedBefore(cutoff);

        verify(service).issuePendingItems(first);
        verify(service).issuePendingItems(second);
    }
}
