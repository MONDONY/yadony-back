package com.yadony.api.common.stripe;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StripeEventSchedulerTest {

    private final StripeEventProcessor processor = mock(StripeEventProcessor.class);
    private final StripeEventScheduler scheduler = new StripeEventScheduler(processor,
            new StripeWebhookProperties(Duration.ofSeconds(10), 3, 3, Duration.ofSeconds(5), true));

    @Test
    void traiteJusquALaTailleDuLot() {
        when(processor.processOne()).thenReturn(true);

        scheduler.poll();

        verify(processor, times(3)).processOne();
    }

    @Test
    void sArreteQuandLInboxEstVide() {
        when(processor.processOne()).thenReturn(true, false);

        scheduler.poll();

        verify(processor, times(2)).processOne();
    }

    @Test
    void connexionImpossible_neRemontePasEtLaisseLePassageSuivantReprendre() {
        // YADONY-BACK-STAGING-11 : pool Hikari sans connexion valide pendant les lenteurs disque.
        when(processor.processOne())
                .thenReturn(true)
                .thenThrow(new CannotCreateTransactionException("Could not open JPA EntityManager"));

        assertThatCode(scheduler::poll).doesNotThrowAnyException();
        verify(processor, times(2)).processOne();
    }

    @Test
    void baseIndisponible_neRemontePas() {
        when(processor.processOne()).thenThrow(new DataAccessResourceFailureException("connection closed"));

        assertThatCode(scheduler::poll).doesNotThrowAnyException();
    }

    @Test
    void autreErreur_remonteToujours() {
        when(processor.processOne()).thenThrow(new IllegalStateException("bug"));

        assertThatThrownBy(scheduler::poll).isInstanceOf(IllegalStateException.class);
    }
}
