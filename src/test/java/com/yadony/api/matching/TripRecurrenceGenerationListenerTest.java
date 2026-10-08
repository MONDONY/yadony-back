package com.yadony.api.matching;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TripRecurrenceGenerationListenerTest {

    @Test
    void onRecurrenceSaved_generatesTripsOfThatRecurrence() {
        TripRecurrenceService service = mock(TripRecurrenceService.class);
        UUID recurrenceId = UUID.randomUUID();

        new TripRecurrenceGenerationListener(service)
                .onRecurrenceSaved(new TripRecurrenceSavedEvent(recurrenceId));

        verify(service).generateForRecurrenceId(recurrenceId);
    }
}
