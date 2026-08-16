package com.yadony.api.requests.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * L'expéditeur a réglé en espèces mais la commission Yadony n'a pas pu être
 * prélevée au voyageur : portefeuille insuffisant et aucune carte de commission
 * exploitable. L'accord n'est pas validé — le voyageur doit recharger son
 * portefeuille (ou enregistrer une carte) pour que l'expéditeur puisse réessayer.
 *
 * <p>Publié depuis une transaction qui va rollback : le listener doit être un
 * {@code @EventListener} simple, jamais {@code AFTER_COMMIT} (qui ne se
 * déclencherait jamais).
 */
public record NegotiationCashCommissionFailedEvent(
    UUID threadId,
    UUID packageRequestId,
    UUID travelerId,
    UUID senderId,
    BigDecimal commissionAmount,
    String currency
) {}
