package com.yadony.api.payments.cash;

import com.yadony.api.requests.event.NegotiationCommissionDeclinedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NegotiationCommissionDeclinedRefundListenerTest {

    @Mock private CashCommissionService cashCommissionService;
    @InjectMocks private NegotiationCommissionDeclinedRefundListener listener;

    private static NegotiationCommissionDeclinedEvent event(UUID threadId, UUID travelerId) {
        return new NegotiationCommissionDeclinedEvent(
                threadId, UUID.randomUUID(), UUID.randomUUID(), travelerId);
    }

    @Test
    @DisplayName("le renoncement déclenche le remboursement, dans le bon ordre d'arguments")
    void onCommissionDeclined_delegatesRefund() {
        UUID threadId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        when(cashCommissionService.refundNegotiationCommissionIfCharged(threadId, travelerId))
                .thenReturn(true);

        listener.onCommissionDeclined(event(threadId, travelerId));

        verify(cashCommissionService).refundNegotiationCommissionIfCharged(threadId, travelerId);
    }

    @Test
    @DisplayName("une erreur de remboursement ne remonte jamais : le renoncement est déjà commité")
    void onCommissionDeclined_swallowsFailures() {
        UUID threadId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        doThrow(new IllegalStateException("Stripe indisponible"))
                .when(cashCommissionService).refundNegotiationCommissionIfCharged(any(), any());

        assertThatCode(() -> listener.onCommissionDeclined(event(threadId, travelerId)))
                .doesNotThrowAnyException();
    }

    /**
     * Garde-fou de câblage transactionnel, pas un test de comportement.
     *
     * <p>{@code declineCommission} mute la ligne du fil et la détient jusqu'à son
     * commit. Un remboursement lancé avant ce commit ouvrirait une transaction
     * {@code REQUIRES_NEW} qui écrit la même ligne : elle bloquerait sur une
     * connexion que l'appelant ne libérera jamais, sans qu'aucun cycle ne soit
     * visible pour PostgreSQL, donc sans détection d'interblocage. Aucun test de
     * comportement ne peut voir cette régression, les mocks n'ouvrant pas de
     * transaction.
     */
    @Test
    @DisplayName("écouteur AFTER_COMMIT et transaction propre, sinon le renoncement se bloque sur lui-même")
    void listener_runsAfterCommitInItsOwnTransaction() throws NoSuchMethodException {
        var method = NegotiationCommissionDeclinedRefundListener.class
                .getMethod("onCommissionDeclined", NegotiationCommissionDeclinedEvent.class);

        assertThat(method.getAnnotation(TransactionalEventListener.class)).isNotNull();
        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
        assertThat(method.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
    }
}
