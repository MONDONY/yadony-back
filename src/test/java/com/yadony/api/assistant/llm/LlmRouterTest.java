package com.yadony.api.assistant.llm;

import com.yadony.api.assistant.provider.ProviderCatalog;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmRouterTest {

    private final OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
    private final ProviderCatalog catalog = mock(ProviderCatalog.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-16T10:00:00Z"));
    private final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now.get(); }
    };
    private final ProviderHealthRegistry health = new ProviderHealthRegistry(clock);
    private final LlmRouter router = new LlmRouter(catalog, client, health, clock);
    private final Function<String, String> identity = s -> s;

    private final LlmProviderConfig a = cfg("a");
    private final LlmProviderConfig b = cfg("b");
    private final LlmProviderConfig c = cfg("c");
    private final LlmProviderConfig d = cfg("d");

    private static LlmProviderConfig cfg(String slug) {
        return new LlmProviderConfig(UUID.randomUUID(), slug, "https://" + slug + ".com", "m", "k", true, null);
    }

    @BeforeEach
    void setUp() {
        when(catalog.activeProviders()).thenReturn(List.of(a, b, c, d));
    }

    @Test
    void premierFournisseurRepond() {
        when(client.complete(eq(a), anyString(), anyString(), any(Duration.class))).thenReturn("ok");
        assertThat(router.complete("s", "u", identity)).contains(new LlmResult<>("a", "ok"));
        verify(client, never()).complete(eq(b), anyString(), anyString(), any(Duration.class));
    }

    @Test
    void basculeSur429Puis500() {
        when(client.complete(eq(a), anyString(), anyString(), any(Duration.class))).thenThrow(new LlmCallException(429, "quota"));
        when(client.complete(eq(b), anyString(), anyString(), any(Duration.class))).thenThrow(new LlmCallException(503, "panne"));
        when(client.complete(eq(c), anyString(), anyString(), any(Duration.class))).thenReturn("ok");
        assertThat(router.complete("s", "u", identity)).contains(new LlmResult<>("c", "ok"));
    }

    @Test
    void basculeSurAdresseBloquee() {
        when(client.complete(eq(a), anyString(), anyString(), any(Duration.class)))
                .thenThrow(new LlmCallException(LlmCallException.BLOCKED_ADDRESS, "adresse non publique"));
        when(client.complete(eq(b), anyString(), anyString(), any(Duration.class))).thenReturn("ok");
        assertThat(router.complete("s", "u", identity)).contains(new LlmResult<>("b", "ok"));
        assertThat(health.tryAcquire(a)).isFalse();
    }

    @Test
    void basculeSurContenuInvalide() {
        when(client.complete(eq(a), anyString(), anyString(), any(Duration.class))).thenReturn("bavard");
        when(client.complete(eq(b), anyString(), anyString(), any(Duration.class))).thenReturn("{}");
        Function<String, String> parser = s -> {
            if (!s.startsWith("{")) throw new LlmCallException(LlmCallException.INVALID_OUTPUT, "pas du JSON");
            return s;
        };
        assertThat(router.complete("s", "u", parser)).contains(new LlmResult<>("b", "{}"));
    }

    @Test
    void troisEssaisAuPlus() {
        when(client.complete(any(), anyString(), anyString(), any(Duration.class))).thenThrow(new LlmCallException(500, "panne"));
        assertThat(router.complete("s", "u", identity)).isEmpty();
        verify(client, times(1)).complete(eq(a), anyString(), anyString(), any(Duration.class));
        verify(client, times(1)).complete(eq(b), anyString(), anyString(), any(Duration.class));
        verify(client, times(1)).complete(eq(c), anyString(), anyString(), any(Duration.class));
        verify(client, never()).complete(eq(d), anyString(), anyString(), any(Duration.class));
    }

    @Test
    void fournisseurALaLimiteDuJourEstSaute() {
        LlmProviderConfig limite = new LlmProviderConfig(a.id(), "a", "https://a.com", "m", "k", true, 1);
        when(catalog.activeProviders()).thenReturn(List.of(limite, b));
        health.tryAcquire(limite);
        when(client.complete(eq(b), anyString(), anyString(), any(Duration.class))).thenReturn("ok");
        assertThat(router.complete("s", "u", identity)).contains(new LlmResult<>("b", "ok"));
        verify(client, never()).complete(eq(limite), anyString(), anyString(), any(Duration.class));
    }

    @Test
    void sortieInexploitableDuParserNeFuitPasLeMessage() {
        when(client.complete(eq(a), anyString(), anyString(), any(Duration.class))).thenReturn("bavard");
        when(client.complete(eq(b), anyString(), anyString(), any(Duration.class))).thenReturn("ok");
        Function<String, String> parser = s -> {
            if (s.equals("bavard")) throw new IllegalStateException("sortie du modèle : bavard");
            return s;
        };
        assertThat(router.complete("s", "u", parser)).contains(new LlmResult<>("b", "ok"));
        assertThat(health.stats(a.id()).lastError()).isEqualTo("Réponse inexploitable");
        assertThat(health.stats(a.id()).lastStatus()).isEqualTo(LlmCallException.INVALID_OUTPUT);
    }

    @Test
    void llmCallExceptionDuParserEstAussiConvertie() {
        when(client.complete(eq(a), anyString(), anyString(), any(Duration.class))).thenReturn("texte");
        when(client.complete(eq(b), anyString(), anyString(), any(Duration.class))).thenReturn("ok");
        Function<String, String> parser = s -> {
            if (s.equals("texte")) throw new LlmCallException(LlmCallException.INVALID_OUTPUT, "texte du modèle");
            return s;
        };
        assertThat(router.complete("s", "u", parser)).contains(new LlmResult<>("b", "ok"));
        assertThat(health.stats(a.id()).lastStatus()).isEqualTo(LlmCallException.INVALID_OUTPUT);
        assertThat(health.stats(a.id()).lastError()).isEqualTo("Réponse inexploitable");
    }

    @Test
    void fournisseurMisDeCoteEstSaute() {
        health.recordFailure(a.id(), 429, "quota");
        when(client.complete(eq(b), anyString(), anyString(), any(Duration.class))).thenReturn("ok");
        assertThat(router.complete("s", "u", identity)).contains(new LlmResult<>("b", "ok"));
        verify(client, never()).complete(eq(a), anyString(), anyString(), any(Duration.class));
    }

    @Test
    void aucunFournisseurActif() {
        when(catalog.activeProviders()).thenReturn(List.of());
        assertThat(router.complete("s", "u", identity)).isEqualTo(Optional.empty());
    }

    @Test
    void chaqueEssaiCompte() {
        when(client.complete(eq(a), anyString(), anyString(), any(Duration.class))).thenThrow(new LlmCallException(500, "panne"));
        when(client.complete(eq(b), anyString(), anyString(), any(Duration.class))).thenReturn("ok");
        router.complete("s", "u", identity);
        assertThat(health.stats(a.id()).requestsToday()).isEqualTo(1);
        assertThat(health.stats(b.id()).successesToday()).isEqualTo(1);
    }

    // ---------- Budget de temps d'une question (ruling R26) ----------

    /** Simule un fournisseur qui consomme {@code seconds} de l'horloge puis échoue sur le délai. */
    private void consumes(LlmProviderConfig provider, long seconds) {
        when(client.complete(eq(provider), anyString(), anyString(), any(Duration.class))).thenAnswer(inv -> {
            now.set(now.get().plusSeconds(seconds));
            throw new LlmCallException(LlmCallException.NETWORK, "Délai dépassé");
        });
    }

    private Duration timeoutGivenTo(LlmProviderConfig provider) {
        ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);
        verify(client).complete(eq(provider), anyString(), anyString(), timeout.capture());
        return timeout.getValue();
    }

    @Test
    void secondFournisseurNeRecoitQueLeTempsRestant() {
        consumes(a, 22);
        when(client.complete(eq(b), anyString(), anyString(), any(Duration.class))).thenReturn("ok");
        assertThat(router.complete("s", "u", identity)).contains(new LlmResult<>("b", "ok"));
        assertThat(timeoutGivenTo(b)).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void sousTroisSecondesRestantesOnNEssaiePlus() {
        consumes(a, 23);
        assertThat(router.complete("s", "u", identity)).isEmpty();
        verify(client, never()).complete(eq(b), anyString(), anyString(), any(Duration.class));
    }

    @Test
    void budgetEpuiseRenvoieVideSansAppelerLeTroisieme() {
        consumes(a, 12);
        consumes(b, 11);
        assertThat(router.complete("s", "u", identity)).isEmpty();
        verify(client, never()).complete(eq(c), anyString(), anyString(), any(Duration.class));
        verify(client, never()).complete(eq(d), anyString(), anyString(), any(Duration.class));
    }

    @Test
    void essaiInterrompuParLeBudgetEstUnEchecReseauSansMiseDeCote() {
        consumes(a, 23);
        router.complete("s", "u", identity);
        assertThat(health.stats(a.id()).lastStatus()).isEqualTo(LlmCallException.NETWORK);
        assertThat(health.stats(a.id()).cooldownUntil()).isNull();
        assertThat(health.tryAcquire(a)).isTrue();
    }

    @Test
    void essaiNonTentePaDeCompteurConsomme() {
        consumes(a, 23);
        router.complete("s", "u", identity);
        assertThat(health.stats(b.id()).requestsToday()).isZero();
    }

    @Test
    void delaiTransmisNeDepasseJamaisVingtSecondesNiLeTempsRestant() {
        consumes(a, 4);
        consumes(b, 10);
        when(client.complete(eq(c), anyString(), anyString(), any(Duration.class))).thenReturn("ok");
        router.complete("s", "u", identity);
        assertThat(timeoutGivenTo(a)).isEqualTo(Duration.ofSeconds(20));
        assertThat(timeoutGivenTo(b)).isEqualTo(Duration.ofSeconds(20));
        assertThat(timeoutGivenTo(c)).isEqualTo(Duration.ofSeconds(11));
    }

    @Test
    void budgetNeCommenceQuALAppelDeComplete() {
        now.set(now.get().plusSeconds(3600));
        when(client.complete(eq(a), anyString(), anyString(), any(Duration.class))).thenReturn("ok");
        router.complete("s", "u", identity);
        assertThat(timeoutGivenTo(a)).isEqualTo(Duration.ofSeconds(20));
    }

    // ---------- Un ERROR par minute au plus (M7) ----------

    private ListAppender<ILoggingEvent> captureLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LlmRouter.class)).addAppender(appender);
        return appender;
    }

    private static long count(ListAppender<ILoggingEvent> appender, Level level) {
        return appender.list.stream().filter(e -> e.getLevel() == level).count();
    }

    @Test
    void deuxEchecsRapprochesNeProduisentQuUnSeulError() {
        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            when(client.complete(any(), anyString(), anyString(), any(Duration.class)))
                    .thenThrow(new LlmCallException(500, "panne"));
            router.complete("s", "u", identity);
            now.set(now.get().plusSeconds(10));
            router.complete("s", "u", identity);
            assertThat(count(logs, Level.ERROR)).isEqualTo(1);

            now.set(now.get().plusSeconds(61));
            router.complete("s", "u", identity);
            assertThat(count(logs, Level.ERROR)).isEqualTo(2);
        } finally {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LlmRouter.class)).detachAppender(logs);
        }
    }

    @Test
    void lesMessagesDeJournalNeContiennentAucuneDonnee() {
        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            when(client.complete(any(), anyString(), anyString(), any(Duration.class)))
                    .thenThrow(new LlmCallException(500, "panne"));
            router.complete("question secrète", "u", identity);
            assertThat(logs.list).isNotEmpty().allSatisfy(e ->
                    assertThat(e.getFormattedMessage()).doesNotContain("secrète").doesNotContain("panne"));
        } finally {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LlmRouter.class)).detachAppender(logs);
        }
    }
}
