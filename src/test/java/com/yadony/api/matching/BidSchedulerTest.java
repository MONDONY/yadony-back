package com.yadony.api.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.matching.events.HandoverAlertEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BidSchedulerTest {

    @Mock BidRepository bidRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    @Test
    void scanAndSendH2Alerts_marksBidBeforePublishingNotificationEvent() {
        BidEntity bid = new BidEntity();
        UUID bidId = UUID.randomUUID();
        ReflectionTestUtils.setField(bid, "id", bidId);
        ReflectionTestUtils.setField(bid, "senderId", UUID.randomUUID());
        bid.setHandoverLocation("Gare du Nord");
        bid.setHandoverWindowStart(LocalDateTime.now().plusHours(1));
        bid.setHandoverWindowEnd(LocalDateTime.now().plusHours(2));
        when(bidRepository.findBidsNeedingH2Alert(any(), any())).thenReturn(List.of(bid));

        new BidScheduler(bidRepository, eventPublisher).scanAndSendH2Alerts();

        assertThat(bid.getH2AlertSentAt()).isNotNull();
        InOrder order = inOrder(bidRepository, eventPublisher);
        order.verify(bidRepository).save(bid);
        order.verify(eventPublisher).publishEvent(any(HandoverAlertEvent.class));
        ArgumentCaptor<HandoverAlertEvent> event = ArgumentCaptor.forClass(HandoverAlertEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().bidId()).isEqualTo(bidId);
    }
}
