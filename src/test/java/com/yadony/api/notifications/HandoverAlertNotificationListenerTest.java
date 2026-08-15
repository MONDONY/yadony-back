package com.yadony.api.notifications;

import static org.mockito.Mockito.verify;

import com.yadony.api.matching.events.HandoverAlertEvent;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HandoverAlertNotificationListenerTest {

    @Mock NotificationDispatcher dispatcher;
    @Test
    void afterCommit_dispatchesNotification() {
        HandoverAlertEvent event = new HandoverAlertEvent(
                UUID.randomUUID(), UUID.randomUUID(), "Gare du Nord",
                LocalDateTime.now().plusHours(2));
        HandoverAlertNotificationListener listener =
                new HandoverAlertNotificationListener(dispatcher);

        listener.onHandoverAlert(event);

        verify(dispatcher).onHandoverAlert(event);
    }
}
