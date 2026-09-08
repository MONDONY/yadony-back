package com.yadony.api.payments.pawapay.events;

import com.yadony.api.payments.pawapay.PawapayOperationKind;
import java.util.UUID;

/**
 * Publié au plus une fois par opération, sur FAILED ou SUBMIT_REJECTED
 * détecté par le poller (transition gagnée par {@code applyTransition}).
 * {@code failureCode}/{@code failureMessage} reflètent ce qui est
 * effectivement en base après la transition (relu après coup), pas
 * nécessairement les paramètres reçus par l'appel qui a déclenché l'événement.
 * <p>
 * Comme {@link PawapayOperationCompletedEvent}, publié dans la transaction
 * d'{@code apply} : la livraison « après commit » dépend de l'écouteur
 * ({@code @TransactionalEventListener(AFTER_COMMIT)} + {@code @Transactional(REQUIRES_NEW)}),
 * pas de cet événement lui-même.
 */
public record PawapayOperationFailedEvent(UUID operationId, PawapayOperationKind kind, UUID paymentId,
                                          String failureCode, String failureMessage) {}
