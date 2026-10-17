package com.yadony.api.ratings;

import com.yadony.api.ratings.events.RatingCreatedEvent;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeliveryConfirmedEventListener")
class DeliveryConfirmedEventListenerTest {

    @Mock private FraudDetectionService fraudDetectionService;
    @InjectMocks private DeliveryConfirmedEventListener listener;

    @Test
    @DisplayName("onRatingCreated lance la détection de farming quand l'auteur est connu")
    void ratingCreatedTriggersFarmingDetection() {
        UUID ratingId = UUID.randomUUID();
        RatingCreatedEvent event = mock(RatingCreatedEvent.class);
        when(event.getRaterId()).thenReturn(UUID.randomUUID());
        when(event.getRatingId()).thenReturn(ratingId);

        listener.onRatingCreated(event);

        verify(fraudDetectionService).detectRatingFarming(ratingId);
    }

    @Test
    @DisplayName("onRatingCreated ignore un avis sans auteur")
    void ratingCreatedWithoutRaterIsIgnored() {
        RatingCreatedEvent event = mock(RatingCreatedEvent.class);
        when(event.getRaterId()).thenReturn(null);

        listener.onRatingCreated(event);

        verify(fraudDetectionService, never()).detectRatingFarming(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("plus aucune méthode n'écoute DeliveryConfirmedEvent (Kilo Pro n'est plus automatique)")
    void noListenerOnDeliveryConfirmedEvent() {
        assertThat(Arrays.stream(DeliveryConfirmedEventListener.class.getDeclaredMethods())
                .noneMatch(m -> m.getParameterTypes().length > 0
                        && m.getParameterTypes()[0] == DeliveryConfirmedEvent.class)).isTrue();
    }
}
