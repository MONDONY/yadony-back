package com.yadony.api.payments.pawapay.events;

import com.yadony.api.payments.pawapay.PawapayOperationKind;
import java.util.UUID;

/** Publié une seule fois par opération, après commit, quand elle atteint COMPLETED. */
public record PawapayOperationCompletedEvent(UUID operationId, PawapayOperationKind kind, UUID paymentId) {}
