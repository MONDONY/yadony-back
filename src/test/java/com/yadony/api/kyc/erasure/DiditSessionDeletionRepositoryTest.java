package com.yadony.api.kyc.erasure;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Base du profil test (son URL H2 crée kyc_schema), pas la base embarquée par défaut. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class DiditSessionDeletionRepositoryTest {

    @Autowired DiditSessionDeletionRepository repository;

    private final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

    @Test
    void claim_prendLeBailUneSeuleFois() {
        DiditSessionDeletionEntity row = repository.saveAndFlush(
                new DiditSessionDeletionEntity(UUID.randomUUID(), "sess-claim", now.minusMinutes(1)));

        assertThat(repository.claim(row.getId(), now, now.plusMinutes(10))).isEqualTo(1);
        // Le bail repousse l'échéance : un second essai concurrent ne prend rien.
        assertThat(repository.claim(row.getId(), now, now.plusMinutes(10))).isZero();
        assertThat(repository.findById(row.getId()).orElseThrow().getLastAttemptAt()).isNotNull();
    }

    @Test
    void claim_ignoreUneLigneClose() {
        DiditSessionDeletionEntity row = new DiditSessionDeletionEntity(UUID.randomUUID(), "sess-done", now.minusMinutes(1));
        row.setStatus(DiditSessionDeletionEntity.Status.DONE);
        row = repository.saveAndFlush(row);

        assertThat(repository.claim(row.getId(), now, now.plusMinutes(10))).isZero();
    }

    @Test
    void findDueIds_neRendQueLesLignesEnAttenteEchues() {
        UUID userId = UUID.randomUUID();
        DiditSessionDeletionEntity due = repository.save(new DiditSessionDeletionEntity(userId, "sess-due", now.minusMinutes(5)));
        repository.save(new DiditSessionDeletionEntity(userId, "sess-later", now.plusHours(1)));
        DiditSessionDeletionEntity failed = new DiditSessionDeletionEntity(userId, "sess-failed", now.minusMinutes(5));
        failed.setStatus(DiditSessionDeletionEntity.Status.FAILED);
        repository.saveAndFlush(failed);

        assertThat(repository.findDueIds(now, PageRequest.of(0, 10))).containsExactly(due.getId());
        assertThat(repository.findPendingIdsByUserId(userId)).hasSize(2);
        assertThat(repository.existsBySessionId("sess-due")).isTrue();
        assertThat(repository.existsBySessionId("sess-absente")).isFalse();
    }
}
