package com.yadony.api.dac7;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Dac7DueDiligenceServiceTest {

    private final Dac7DueDiligenceLogRepository repository = mock(Dac7DueDiligenceLogRepository.class);
    private final Dac7DueDiligenceService service = new Dac7DueDiligenceService(repository);
    private final UUID userId = UUID.randomUUID();

    @Test
    void enregistreUneLigneAvecSaNoteNettoyee() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        UUID admin = UUID.randomUUID();

        service.record(userId, Dac7DueDiligenceAction.TEAM_DECISION, Dac7DueDiligenceChannel.ADMIN, admin,
                "  Compte conservé, relance par téléphone  ");

        ArgumentCaptor<Dac7DueDiligenceLogEntity> saved = ArgumentCaptor.forClass(Dac7DueDiligenceLogEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getAction()).isEqualTo(Dac7DueDiligenceAction.TEAM_DECISION);
        assertThat(saved.getValue().getChannel()).isEqualTo(Dac7DueDiligenceChannel.ADMIN);
        assertThat(saved.getValue().getActorId()).isEqualTo(admin);
        assertThat(saved.getValue().getNote()).isEqualTo("Compte conservé, relance par téléphone");
    }

    @Test
    void noteVide_enregistreeCommeAbsente() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        Dac7DueDiligenceLogEntity entry = service.record(userId, Dac7DueDiligenceAction.REQUEST,
                Dac7DueDiligenceChannel.PUSH, null, "   ");
        assertThat(entry.getNote()).isNull();
    }

    @Test
    void noteTropLongue_refusee() {
        assertThatThrownBy(() -> service.record(userId, Dac7DueDiligenceAction.REMINDER_1,
                Dac7DueDiligenceChannel.EMAIL, null, "x".repeat(501)))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("500");
        verify(repository, never()).save(any());
    }

    @Test
    void champsObligatoires() {
        assertThatThrownBy(() -> service.record(null, Dac7DueDiligenceAction.REQUEST,
                Dac7DueDiligenceChannel.PUSH, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(userId, null, Dac7DueDiligenceChannel.PUSH, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(userId, Dac7DueDiligenceAction.REQUEST, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }
}
