package com.yadony.api.payments.pawapay.events;

import com.yadony.api.payments.pawapay.PawapayOperationKind;
import java.util.UUID;

/**
 * Publié au plus une fois par opération, quand elle atteint COMPLETED
 * (transition gagnée par {@code applyTransition} — voir {@code PawapayOperationService#apply}).
 * <p>
 * Publié à l'intérieur de la transaction d'{@code apply} : la livraison
 * « après commit » n'est PAS une garantie de cet événement lui-même, elle
 * dépend entièrement de l'écouteur. Pour ne traiter cette notification
 * qu'une fois la ligne réellement committée (donc visible par toute autre
 * transaction, ex. celle qui déclenchera le versement), l'écouteur doit être
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} combiné à
 * {@code @Transactional(propagation = REQUIRES_NEW)} — jamais un
 * {@code @EventListener} seul sur un traitement d'argent (voir les règles du
 * projet sur les listeners de paiement).
 */
public record PawapayOperationCompletedEvent(UUID operationId, PawapayOperationKind kind, UUID paymentId) {}
