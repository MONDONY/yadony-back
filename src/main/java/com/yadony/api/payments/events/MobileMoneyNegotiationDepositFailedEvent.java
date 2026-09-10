package com.yadony.api.payments.events;

import java.util.UUID;

/** Dépôt pawaPay échoué (PIN refusé, solde insuffisant, opérateur) : le fil revient à « à payer ». */
public record MobileMoneyNegotiationDepositFailedEvent(UUID threadId, UUID paymentId, String failureCode) {}
