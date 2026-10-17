package com.yadony.api.assistant.quota;

import com.yadony.api.assistant.AssistantQuotaGuard;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.PlatformSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Questions du jour en base (H2, mode PostgreSQL) : mêmes requêtes que la prod. */
@SpringBootTest
@ActiveProfiles("test")
class JpaAssistantUserDailyUsageStoreTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);

    @Autowired JpaAssistantUserDailyUsageStore store;
    @Autowired AssistantUserDailyUsageRepository repository;

    private static String uid() {
        return "uid-" + UUID.randomUUID();
    }

    @Test
    void leQuotaTientSousConcurrenceEtChaqueQuestionASonRang() throws Exception {
        String uid = uid();
        List<Integer> accordees = AssistantUserDailyUsageConcurrency.consumeConcurrently(store, uid, DAY, 7, 10, 4);
        assertThat(accordees).containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 7).boxed().toList());
        assertThat(repository.findQuestions(uid, DAY)).contains(7);
    }

    @Test
    void unJourParLigneEtRefusNonCompte() {
        String uid = uid();
        assertThat(store.tryConsume(uid, DAY, 2)).isEqualTo(1);
        assertThat(store.tryConsume(uid, DAY, 2)).isEqualTo(2);
        assertThat(store.tryConsume(uid, DAY, 2)).isEqualTo(AssistantUserDailyUsageStore.REFUSED);
        assertThat(store.tryConsume(uid, DAY, 2)).isEqualTo(AssistantUserDailyUsageStore.REFUSED);
        assertThat(repository.findQuestions(uid, DAY)).contains(2);
        assertThat(store.tryConsume(uid, DAY.plusDays(1), 2)).isEqualTo(1);
        assertThat(store.tryConsume(uid(), DAY, 2)).isEqualTo(1);
    }

    @Test
    void quotaNulRefuseSansCompter() {
        String uid = uid();
        assertThat(store.tryConsume(uid, DAY, 0)).isEqualTo(AssistantUserDailyUsageStore.REFUSED);
        assertThat(repository.findQuestions(uid, DAY)).contains(0);
    }

    /** Redémarrage simulé : un nouveau garde sur la même base relit le compte du jour. */
    @Test
    void unNouveauGardeRelitLeCompteEnBase() {
        String uid = uid();
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.assistantDailyQuota()).thenReturn(3);
        Clock clock = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC);

        AssistantQuotaGuard avant = new AssistantQuotaGuard(store, settings, clock);
        assertThat(avant.consume(uid)).isEqualTo(2);
        assertThat(avant.consume(uid)).isEqualTo(1);

        AssistantQuotaGuard apres = new AssistantQuotaGuard(store, settings, clock);
        assertThat(apres.consume(uid)).isZero();
        assertThatThrownBy(() -> apres.consume(uid)).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(429);
            assertThat(e.getErrorCode()).isEqualTo("assistant-quota-exceeded");
            assertThat(e.getProperties()).containsEntry("retryAfter", "2026-10-17T00:00:00Z");
        });

        AssistantQuotaGuard lendemain = new AssistantQuotaGuard(store, settings,
                Clock.fixed(Instant.parse("2026-10-17T00:00:01Z"), ZoneOffset.UTC));
        assertThat(lendemain.consume(uid)).isEqualTo(2);
    }

    @Test
    void laPurgeSupprimeLesJoursDePlusDeTrenteJours() {
        String uid = uid();
        LocalDate today = LocalDate.of(2026, 12, 1);
        store.tryConsume(uid, today.minusDays(31), 5);
        store.tryConsume(uid, today.minusDays(30), 5);
        store.tryConsume(uid, today, 5);

        new AssistantUserDailyUsageCleanupScheduler(repository,
                Clock.fixed(Instant.parse("2026-12-01T03:30:00Z"), ZoneOffset.UTC)).purgeOldDays();

        assertThat(repository.findQuestions(uid, today.minusDays(31))).isEmpty();
        assertThat(repository.findQuestions(uid, today.minusDays(30))).contains(1);
        assertThat(repository.findQuestions(uid, today)).contains(1);
    }
}
