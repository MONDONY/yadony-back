package com.yadony.api.payments;

import com.yadony.api.requests.event.NegotiationCancelledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NegotiationCancelledEscrowListenerTest {

    @Mock private PaymentService paymentService;

    private NegotiationCancelledEscrowListener listener;

    private final UUID threadId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID byUserId = UUID.randomUUID();
    private final UUID toUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new NegotiationCancelledEscrowListener(paymentService);
    }

    private NegotiationCancelledEvent event(boolean releaseEscrow) {
        return new NegotiationCancelledEvent(threadId, requestId, byUserId, toUserId, "Alice", releaseEscrow, false);
    }

    @Test
    @DisplayName("releaseEscrow=true → cancelNegotiationEscrow(threadId, reason) appelé")
    void releaseTrue_cancelsEscrow() {
        when(paymentService.cancelNegotiationEscrow(threadId, "negotiation-cancelled")).thenReturn(true);

        listener.onNegotiationCancelled(event(true));

        verify(paymentService).cancelNegotiationEscrow(threadId, "negotiation-cancelled");
    }

    /**
     * OPEN / AWAITING_TRIP cancels (releaseEscrow=false) no longer skip via a body
     * guard — Spring itself never dispatches the listener for them, enforced by the
     * {@code condition = "#event.releaseEscrow()"} SpEL on {@code @TransactionalEventListener}.
     * That dispatch-time skip isn't exercisable by directly invoking the method (no
     * Spring context here), so it's asserted declaratively below instead.
     */
    @Test
    @DisplayName("@TransactionalEventListener : AFTER_COMMIT + condition #event.releaseEscrow()")
    void annotatedWithReleaseEscrowCondition() throws NoSuchMethodException {
        Method method = NegotiationCancelledEscrowListener.class
            .getMethod("onNegotiationCancelled", NegotiationCancelledEvent.class);
        TransactionalEventListener ann = method.getAnnotation(TransactionalEventListener.class);

        assertThat(ann).isNotNull();
        assertThat(ann.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(ann.condition()).isEqualTo("#event.releaseEscrow()");
    }

    @Test
    @DisplayName("cancelNegotiationEscrow=false (hold non annulable) → loggé, pas d'exception")
    void releaseFails_isLoggedNotThrown() {
        when(paymentService.cancelNegotiationEscrow(threadId, "negotiation-cancelled")).thenReturn(false);

        assertThatNoException().isThrownBy(() -> listener.onNegotiationCancelled(event(true)));

        verify(paymentService).cancelNegotiationEscrow(threadId, "negotiation-cancelled");
    }

    @Test
    @DisplayName("erreur runtime du service → avalée (la cancellation est déjà commitée)")
    void serviceThrows_isSwallowed() {
        when(paymentService.cancelNegotiationEscrow(threadId, "negotiation-cancelled"))
            .thenThrow(new RuntimeException("stripe boom"));

        assertThatNoException().isThrownBy(() -> listener.onNegotiationCancelled(event(true)));

        verify(paymentService).cancelNegotiationEscrow(threadId, "negotiation-cancelled");
    }
}
