package com.yadony.api.cancellation;

import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SenderNoShowBidCancelListenerTest {

    @Mock
    BidRepository bidRepository;

    @InjectMocks
    SenderNoShowBidCancelListener listener;

    private static final UUID BID_ID = UUID.randomUUID();

    private CancellationConfirmedEvent event(CancellationReason reason) {
        return new CancellationConfirmedEvent(BID_ID, UUID.randomUUID(), reason);
    }

    private BidEntity bid(BidStatus status) {
        BidEntity b = new BidEntity();
        b.setStatus(status);
        return b;
    }

    @Test
    void senderNoShow_acceptedBid_cancels() {
        BidEntity b = bid(BidStatus.ACCEPTED);
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(b));

        listener.onCancellationConfirmed(event(CancellationReason.SENDER_NO_SHOW));

        assertThat(b.getStatus()).isEqualTo(BidStatus.CANCELLED);
        // FLUTTER-QQ : le motif ferme le contact et la conversation sans lire l'annulation.
        assertThat(b.getRejectionReason()).isEqualTo(BidEntity.REJECTION_SENDER_NO_SHOW);
        verify(bidRepository).save(b);
    }

    @Test
    void otherReason_noOp() {
        listener.onCancellationConfirmed(
                event(CancellationReason.SENDER_CANCEL_AFTER_HANDOVER));

        verifyNoInteractions(bidRepository);
    }

    @Test
    void nonAcceptedBid_noOp() {
        BidEntity b = bid(BidStatus.NO_SHOW);
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(b));

        listener.onCancellationConfirmed(event(CancellationReason.SENDER_NO_SHOW));

        assertThat(b.getStatus()).isEqualTo(BidStatus.NO_SHOW);
        verify(bidRepository, never()).save(any());
    }
}
