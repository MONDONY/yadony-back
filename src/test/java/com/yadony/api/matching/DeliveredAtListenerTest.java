package com.yadony.api.matching;

import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveredAtListenerTest {

    @Mock BidRepository bidRepository;
    @InjectMocks DeliveredAtListener listener;

    @Test
    void renseigneDeliveredAtALaPremiereConfirmation() {
        UUID bidId = UUID.randomUUID();
        BidEntity bid = new BidEntity();
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), UUID.randomUUID()));

        assertThat(bid.getDeliveredAt()).isNotNull();
        verify(bidRepository).save(bid);
    }

    @Test
    void neRepoussePasUneDateDejaPresente() {
        UUID bidId = UUID.randomUUID();
        BidEntity bid = new BidEntity();
        LocalDateTime first = LocalDateTime.of(2026, 10, 1, 12, 0);
        bid.markDelivered(first);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), UUID.randomUUID()));

        assertThat(bid.getDeliveredAt()).isEqualTo(first);
    }

    @Test
    void bidIntrouvableNeLevePas() {
        UUID bidId = UUID.randomUUID();
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), UUID.randomUUID()));

        verify(bidRepository, never()).save(any());
    }
}
