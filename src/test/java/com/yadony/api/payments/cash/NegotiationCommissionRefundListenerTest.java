package com.yadony.api.payments.cash;

import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.event.NegotiationCancelledEvent;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NegotiationCommissionRefundListenerTest {

    @Mock private CashCommissionService cashCommissionService;
    @InjectMocks private NegotiationCommissionRefundListener listener;

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
        var method = NegotiationCommissionRefundListener.class
                .getMethod("onCommissionDeclined", NegotiationCommissionDeclinedEvent.class);

        assertThat(method.getAnnotation(TransactionalEventListener.class)).isNotNull();
        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
        assertThat(method.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    // Le drapeau de remboursement est dérivé par le record : seul un fil qui
    // était AWAITING_COMMISSION peut devoir un remboursement de commission.
    private static NegotiationCancelledEvent cancelled(UUID threadId, UUID byUserId, UUID toUserId) {
        return new NegotiationCancelledEvent(
                threadId, UUID.randomUUID(), byUserId, toUserId, "Alice",
                NegotiationThreadStatus.AWAITING_COMMISSION);
    }

    @Test
    @DisplayName("une négociation annulée rembourse la commission déjà débitée")
    void onNegotiationCancelled_delegatesRefund() {
        UUID threadId = UUID.randomUUID();
        UUID byUserId = UUID.randomUUID();
        when(cashCommissionService.refundNegotiationCommissionIfCharged(threadId, byUserId))
                .thenReturn(true);

        listener.onNegotiationCancelled(cancelled(threadId, byUserId, UUID.randomUUID()));

        verify(cashCommissionService).refundNegotiationCommissionIfCharged(threadId, byUserId);
    }

    /**
     * L'acteur transmis sert d'auteur dans {@code audit_log}, table immuable :
     * une écriture financière attribuée à la mauvaise personne ne se corrige
     * jamais. {@code toUserId} désigne « l'autre partie », donc l'expéditeur
     * quand c'est le voyageur qui a fermé le fil — c'est {@code byUserId},
     * l'auteur de l'annulation, qui doit être tracé.
     */
    @Test
    @DisplayName("l'acteur tracé est l'auteur de l'annulation, jamais l'autre partie")
    void onNegotiationCancelled_auditsTheCanceller() {
        UUID threadId = UUID.randomUUID();
        UUID canceller = UUID.randomUUID();
        UUID otherParty = UUID.randomUUID();

        listener.onNegotiationCancelled(cancelled(threadId, canceller, otherParty));

        verify(cashCommissionService).refundNegotiationCommissionIfCharged(threadId, canceller);
        verify(cashCommissionService, never())
                .refundNegotiationCommissionIfCharged(threadId, otherParty);
    }

    @Test
    @DisplayName("une erreur de remboursement ne remonte jamais : l'annulation est déjà commitée")
    void onNegotiationCancelled_swallowsFailures() {
        doThrow(new IllegalStateException("Stripe indisponible"))
                .when(cashCommissionService).refundNegotiationCommissionIfCharged(any(), any());

        assertThatCode(() -> listener.onNegotiationCancelled(
                cancelled(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
                .doesNotThrowAnyException();
    }

    /**
     * Même garde-fou de câblage que ci-dessus, plus la condition SpEL : sans
     * elle, chaque annulation de négociation — l'immense majorité n'ayant aucune
     * commission en jeu — irait relire Stripe pour rien.
     */
    @Test
    @DisplayName("écouteur AFTER_COMMIT, transaction propre, et conditionné au drapeau de remboursement")
    void cancelledListener_runsAfterCommitInItsOwnTransaction() throws NoSuchMethodException {
        var method = NegotiationCommissionRefundListener.class
                .getMethod("onNegotiationCancelled", NegotiationCancelledEvent.class);

        assertThat(method.getAnnotation(TransactionalEventListener.class)).isNotNull();
        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(method.getAnnotation(TransactionalEventListener.class).condition())
                .isEqualTo("#event.refundCommission()");
        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
        assertThat(method.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
    }
}
