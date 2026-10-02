package com.yadony.api.calls;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Transitions atomiques : un seul des webhooks concurrents fait passer l'appel à un statut terminal. */
@SpringBootTest
@ActiveProfiles("test")
class CallRepositoryIntegrationTest {

    @Autowired CallRepository calls;
    @Autowired TransactionTemplate tx;

    private Integer run(org.springframework.transaction.support.TransactionCallback<Integer> action) {
        return tx.execute(action);
    }

    private CallEntity persist() {
        return calls.saveAndFlush(new CallEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID().toString()));
    }

    @Test
    void uneSeuleFinGagne() {
        CallEntity call = persist();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Integer first = tx.execute(s -> calls.finishIfLive(call.getId(), CallStatus.MISSED, now, null, null));
        Integer second = tx.execute(s -> calls.finishIfLive(call.getId(), CallStatus.ENDED, now, now, 10));
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(calls.findById(call.getId()).orElseThrow().getStatus()).isEqualTo(CallStatus.MISSED);
    }

    @Test
    void decrocherSeulementDepuisLaSonnerie() {
        CallEntity call = persist();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        assertThat(run(s -> calls.markAnswered(call.getId(), now))).isEqualTo(1);
        assertThat(run(s -> calls.markAnswered(call.getId(), now))).isZero();
        assertThat(run(s -> calls.finishIfLive(call.getId(), CallStatus.ENDED, now.plusSeconds(90), now, 90))).isEqualTo(1);
        CallEntity done = calls.findById(call.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(CallStatus.ENDED);
        assertThat(done.getDurationSeconds()).isEqualTo(90);
        assertThat(run(s -> calls.markAnswered(call.getId(), now))).isZero();
    }

    @Test
    void ecarterUneLigneLaSortDesAppelsEnCours() {
        CallEntity call = persist();
        assertThat(run(s -> calls.discard(call.getId(), java.time.LocalDateTime.now()))).isEqualTo(1);
        assertThat(calls.findById(call.getId())).isEmpty();
    }
}
