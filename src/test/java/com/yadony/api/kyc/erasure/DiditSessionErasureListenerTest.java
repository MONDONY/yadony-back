package com.yadony.api.kyc.erasure;

import com.yadony.api.auth.events.IdentitySessionsErasureRequestedEvent;
import com.yadony.api.kyc.KycDecisionKind;
import com.yadony.api.kyc.KycRefusedSessionEntity;
import com.yadony.api.kyc.KycRefusedSessionRepository;
import com.yadony.api.kyc.provider.VerificationProviderKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiditSessionErasureListenerTest {

    @Mock DiditSessionErasureService erasureService;
    @Mock KycRefusedSessionRepository refusedSessionRepository;
    @InjectMocks DiditSessionErasureListener listener;

    static final UUID USER_ID = UUID.randomUUID();

    private static KycRefusedSessionEntity refused(VerificationProviderKind provider, String sessionId) {
        return new KycRefusedSessionEntity(USER_ID, provider, sessionId, KycDecisionKind.REJECTED, null,
                LocalDateTime.now());
    }

    @Test
    @SuppressWarnings("unchecked")
    void enqueue_ajouteLesSessionsDiditRefuseesMaisPasCellesDeStripe() {
        when(refusedSessionRepository.findAllByUserId(USER_ID)).thenReturn(List.of(
                refused(VerificationProviderKind.DIDIT, "sess-refusee"),
                refused(VerificationProviderKind.STRIPE, "vs_stripe"),
                refused(null, "sess-sans-fournisseur")));
        when(erasureService.enqueue(eq(USER_ID), org.mockito.ArgumentMatchers.any())).thenReturn(2);

        listener.enqueue(new IdentitySessionsErasureRequestedEvent(USER_ID, List.of("sess-courante")));

        ArgumentCaptor<Collection<String>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(erasureService).enqueue(eq(USER_ID), ids.capture());
        assertThat(ids.getValue()).containsExactly("sess-courante", "sess-refusee");
    }

    @Test
    void enqueue_sansAucuneSession_rienDeCree() {
        when(refusedSessionRepository.findAllByUserId(USER_ID)).thenReturn(List.of());

        listener.enqueue(new IdentitySessionsErasureRequestedEvent(USER_ID, null));

        verify(erasureService).enqueue(USER_ID, List.of());
    }

    @Test
    void eraseAfterCommit_essaieImmediatement() {
        listener.eraseAfterCommit(new IdentitySessionsErasureRequestedEvent(USER_ID, List.of()));

        verify(erasureService).attemptPendingFor(USER_ID);
    }

    @Test
    void eraseAfterCommit_unEchecNeRemontePas() {
        doThrow(new IllegalStateException("db")).when(erasureService).attemptPendingFor(USER_ID);

        assertThatCode(() -> listener.eraseAfterCommit(new IdentitySessionsErasureRequestedEvent(USER_ID, List.of())))
                .doesNotThrowAnyException();
    }
}
