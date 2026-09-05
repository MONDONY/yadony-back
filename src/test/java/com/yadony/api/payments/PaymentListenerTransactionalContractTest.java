package com.yadony.api.payments;

import com.yadony.api.matching.AnnouncementService;
import com.yadony.api.payments.mobilemoney.MobileMoneyDepositOutcomeListener;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutOutcomeListener;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrat règle critique #18 (CLAUDE.md) : tout listener de paiement doit écouter en
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} — un simple
 * {@code @EventListener} lit des données non commitées et peut déclencher un
 * remboursement Stripe pour une transaction métier finalement rollbackée.
 *
 * <p>Depuis la migration vers {@link RefundProcessor}, les 4 listeners mono-paiement
 * (BidRejected / ParcelRefused / NoShow / BidExpired) délèguent le remboursement au
 * processor : ils n'ouvrent plus de transaction eux-mêmes ({@code REQUIRES_NEW} vit
 * désormais sur {@code RefundProcessor#processRefund}). Les listeners qui mutent
 * encore l'état en propre conservent {@code @Transactional(REQUIRES_NEW)}.
 */
class PaymentListenerTransactionalContractTest {

    /** Listeners délégant à RefundProcessor : AFTER_COMMIT requis, pas de @Transactional propre. */
    static Stream<Arguments> afterCommitListeners() {
        return Stream.of(
                Arguments.of(BidRejectedEventListener.class, "handleBidRejected"),
                Arguments.of(ParcelRefusedEventListener.class, "onParcelRefused"),
                Arguments.of(NoShowEventListener.class, "onVoyageurNoShow"),
                Arguments.of(BidExpiredOnDepartureEventListener.class, "handleBidExpired"),
                Arguments.of(TripCancelledEventListener.class, "handleTripCancelled"),
                Arguments.of(SenderNoShowConfirmedListener.class, "onCancellationConfirmed")
        );
    }

    /** Listeners portant encore leur propre transaction : AFTER_COMMIT + REQUIRES_NEW. */
    static Stream<Arguments> fullContractListeners() {
        return Stream.of(
                Arguments.of(DeliveryEventListener.class, "handleDeliveryConfirmed"),
                Arguments.of(NegotiationCaptureListener.class, "onEscrowReady"),
                Arguments.of(BidAcceptedEventListener.class, "onBidAccepted"),
                // Tâche 14, avancé de la tâche 19 (Ronde 1, point 8) : les deux premiers
                // écouteurs des événements pawaPay génériques (PawapayOperationCompletedEvent/
                // FailedEvent), publiés à l'intérieur de la transaction qui applique la
                // transition — mêmes enjeux, même contrat, doivent être découvrables ici.
                Arguments.of(MobileMoneyDepositOutcomeListener.class, "onCompleted"),
                Arguments.of(MobileMoneyDepositOutcomeListener.class, "onFailed"),
                // Tâche 16 : jumeaux payout des deux écouteurs ci-dessus — même contrat.
                Arguments.of(MobileMoneyPayoutOutcomeListener.class, "onCompleted"),
                Arguments.of(MobileMoneyPayoutOutcomeListener.class, "onFailed")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("afterCommitListeners")
    void delegating_listener_uses_after_commit(Class<?> listenerClass, String methodName) {
        Method handler = handlerMethod(listenerClass, methodName);

        TransactionalEventListener tel = handler.getAnnotation(TransactionalEventListener.class);
        assertThat(tel)
                .as("%s#%s doit être @TransactionalEventListener", listenerClass.getSimpleName(), methodName)
                .isNotNull();
        assertThat(tel.phase())
                .as("%s#%s doit écouter en AFTER_COMMIT", listenerClass.getSimpleName(), methodName)
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fullContractListeners")
    void payment_listener_uses_after_commit_and_requires_new(Class<?> listenerClass,
                                                             String methodName) {
        Method handler = handlerMethod(listenerClass, methodName);

        TransactionalEventListener tel = handler.getAnnotation(TransactionalEventListener.class);
        assertThat(tel)
                .as("%s#%s doit être @TransactionalEventListener", listenerClass.getSimpleName(), methodName)
                .isNotNull();
        assertThat(tel.phase())
                .as("%s#%s doit écouter en AFTER_COMMIT", listenerClass.getSimpleName(), methodName)
                .isEqualTo(TransactionPhase.AFTER_COMMIT);

        Transactional tx = handler.getAnnotation(Transactional.class);
        assertThat(tx)
                .as("%s#%s doit être @Transactional", listenerClass.getSimpleName(), methodName)
                .isNotNull();
        assertThat(tx.propagation())
                .as("%s#%s doit utiliser REQUIRES_NEW", listenerClass.getSimpleName(), methodName)
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void refund_processor_uses_requires_new() throws NoSuchMethodException {
        Method m = RefundProcessor.class.getMethod("processRefund",
                UUID.class, String.class, UUID.class, Map.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void trigger_in_progress_transitions_is_transactional() throws NoSuchMethodException {
        // BidExpiredOnDepartureEvent est écouté en AFTER_COMMIT : publié hors
        // transaction (chemin scheduler), l'event serait silencieusement perdu et
        // les remboursements des bids expirés jamais déclenchés.
        Method m = AnnouncementService.class.getMethod("triggerInProgressTransitions");
        assertThat(m.getAnnotation(Transactional.class))
                .as("AnnouncementService#triggerInProgressTransitions doit être @Transactional "
                        + "(publisher d'un event écouté en AFTER_COMMIT)")
                .isNotNull();
    }

    private static Method handlerMethod(Class<?> listenerClass, String methodName) {
        return Arrays.stream(listenerClass.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        listenerClass.getSimpleName() + "#" + methodName + " introuvable"));
    }
}
