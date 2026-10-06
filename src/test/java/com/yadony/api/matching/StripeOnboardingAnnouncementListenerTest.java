package com.yadony.api.matching;

import com.yadony.api.payments.events.StripeOnboardingCompletedEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StripeOnboardingAnnouncementListenerTest {

    private final AnnouncementService announcementService = mock(AnnouncementService.class);
    private final StripeOnboardingAnnouncementListener listener =
            new StripeOnboardingAnnouncementListener(announcementService);

    @Test
    void ouvreLesTrajetsALaCarteDuVoyageur() {
        UUID userId = UUID.randomUUID();

        listener.onStripeOnboardingCompleted(new StripeOnboardingCompletedEvent(userId));

        verify(announcementService).enableCardOnOpenAnnouncements(userId);
    }

    @Test
    void uneErreurNeRemontePasJusquAuWebhookStripe() {
        UUID userId = UUID.randomUUID();
        when(announcementService.enableCardOnOpenAnnouncements(userId))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> listener.onStripeOnboardingCompleted(new StripeOnboardingCompletedEvent(userId)))
                .doesNotThrowAnyException();
    }
}
