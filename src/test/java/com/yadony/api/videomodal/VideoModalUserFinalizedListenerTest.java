package com.yadony.api.videomodal;

import com.yadony.api.auth.FinalizationReason;
import com.yadony.api.auth.events.UserFinalizedEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.yadony.api.videomodal.VideoModalFixtures.NOW;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class VideoModalUserFinalizedListenerTest {

    @Test
    void lesReponsesDuCompteFinaliseSontSupprimees() {
        VideoModalResponseRepository repo = mock(VideoModalResponseRepository.class);
        UUID userId = UUID.randomUUID();
        new VideoModalUserFinalizedListener(repo, Clock.fixed(NOW, ZoneOffset.UTC))
                .onUserFinalized(new UserFinalizedEvent(userId, FinalizationReason.values()[0]));
        verify(repo).softDeleteByUserId(userId, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }
}
