package com.yadony.api.dac7;

import com.yadony.api.matching.events.BidAcceptedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Invite DAC7 après une acceptation : jamais bloquante, rien quand DAC7 est éteint (test 15 de la spec). */
@DisplayName("DAC7 : invite après l'acceptation d'une réservation")
class Dac7AcceptanceListenerTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    private final Dac7ReminderService reminders = mock(Dac7ReminderService.class);
    private final UUID traveler = UUID.randomUUID();
    private final BidAcceptedEvent event = new BidAcceptedEvent(UUID.randomUUID(), UUID.randomUUID(), traveler,
            UUID.randomUUID());

    private Dac7AcceptanceListener listener(Boolean enabled) {
        return new Dac7AcceptanceListener(new Dac7Properties(enabled, null), reminders,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("test 15 : drapeau éteint (défaut) → rien n'est lu ni écrit")
    void eteint_rien() {
        listener(null).onBidAccepted(event);
        listener(false).onBidAccepted(event);
        verifyNoInteractions(reminders);
    }

    @Test
    @DisplayName("allumé : la demande est confiée au service des demandes et rappels")
    void allume_demande() {
        listener(true).onBidAccepted(event);
        verify(reminders).requestAtAcceptance(traveler, NOW);
    }

    @Test
    @DisplayName("panne (base, prestataire) : avalée, l'acceptation n'en sait rien")
    void panne_avalee() {
        when(reminders.requestAtAcceptance(any(), any())).thenThrow(new IllegalStateException("didit"));
        assertThatCode(() -> listener(true).onBidAccepted(event)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("événement sans voyageur : ignoré")
    void sansVoyageur() {
        listener(true).onBidAccepted(new BidAcceptedEvent(UUID.randomUUID(), UUID.randomUUID(), null, null));
        verifyNoInteractions(reminders);
    }
}
