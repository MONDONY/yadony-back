package com.yadony.api.matching;

import com.yadony.api.tracking.events.ParcelInTransitEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TripUnderwayListenerTest {

    @Mock AnnouncementService announcementService;
    @InjectMocks TripUnderwayListener listener;

    @Test
    void onParcelInTransit_marksTripUnderway() {
        UUID annId = UUID.randomUUID();

        listener.onParcelInTransit(new ParcelInTransitEvent(UUID.randomUUID(), annId));

        verify(announcementService).markUnderway(annId, "PARCEL_IN_TRANSIT");
    }

    @Test
    void onParcelInTransit_failureIsSwallowed() {
        UUID annId = UUID.randomUUID();
        doThrow(new IllegalStateException("boom"))
                .when(announcementService).markUnderway(annId, "PARCEL_IN_TRANSIT");

        assertThatCode(() -> listener.onParcelInTransit(new ParcelInTransitEvent(UUID.randomUUID(), annId)))
                .doesNotThrowAnyException();
    }
}
