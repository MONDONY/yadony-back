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
        return listener(enabled, true, Runnable::run);
    }

    private Dac7AcceptanceListener listener(Boolean enabled, boolean remindersEnabled,
                                            java.util.concurrent.Executor executor) {
        return new Dac7AcceptanceListener(new Dac7Properties(enabled, null),
                Dac7ReminderProperties.defaults(remindersEnabled), reminders, Clock.fixed(NOW, ZoneOffset.UTC),
                executor);
    }

    @Test
    @DisplayName("test 15 : drapeau éteint (défaut) → rien n'est lu ni écrit")
    void eteint_rien() {
        listener(null).onBidAccepted(event);
        listener(false).onBidAccepted(event);
        verifyNoInteractions(reminders);
    }

    @Test
    @DisplayName("I1 : collecte allumée mais messages DAC7 (rappels) éteints : aucune invite, rien mis en file")
    void rappelsEteints_aucuneInvite() {
        java.util.concurrent.Executor executor = mock(java.util.concurrent.Executor.class);
        listener(true, false, executor).onBidAccepted(event);
        listener(true, false, executor).onPackageRequestAccepted(negotiated());
        verifyNoInteractions(reminders, executor);
    }

    @Test
    @DisplayName("I3 : le travail part sur l'exécuteur dédié ; file pleine, invite abandonnée sans erreur")
    void executeurDedie_filePleine() {
        java.util.concurrent.Executor full = command -> {
            throw new java.util.concurrent.RejectedExecutionException("plein");
        };
        assertThatCode(() -> listener(true, true, full).onBidAccepted(event)).doesNotThrowAnyException();
        verifyNoInteractions(reminders);

        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        listener(true, true, queued::add).onBidAccepted(event);
        verifyNoInteractions(reminders);
        queued.get(0).run();
        verify(reminders).requestAtAcceptance(traveler, NOW);
    }

    @Test
    @DisplayName("l'exécuteur dédié est étroit et refuse au-delà de sa file")
    void configurationDeLExecuteur() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                new Dac7ExecutorConfig().dac7Executor();
        try {
            org.assertj.core.api.Assertions.assertThat(executor.getMaxPoolSize()).isEqualTo(2);
            org.assertj.core.api.Assertions.assertThat(executor.getQueueCapacity()).isEqualTo(100);
            org.assertj.core.api.Assertions.assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(java.util.concurrent.ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            executor.shutdown();
        }
    }

    private com.yadony.api.requests.event.PackageRequestAcceptedEvent negotiated() {
        return new com.yadony.api.requests.event.PackageRequestAcceptedEvent(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), traveler, java.math.BigDecimal.TEN, null, java.math.BigDecimal.ONE, null,
                null, null, null, null, null, null, null, java.util.List.of(), null, null, null);
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

    @Test
    @DisplayName("demande de colis négociée acceptée : même invite ; éteint, rien")
    void demandeNegociee() {
        com.yadony.api.requests.event.PackageRequestAcceptedEvent accepted = negotiated();
        listener(false).onPackageRequestAccepted(accepted);
        verifyNoInteractions(reminders);
        listener(true).onPackageRequestAccepted(accepted);
        verify(reminders).requestAtAcceptance(traveler, NOW);
    }
}
