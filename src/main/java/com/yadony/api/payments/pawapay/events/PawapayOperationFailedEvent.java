package com.yadony.api.payments.pawapay.events;

import com.yadony.api.payments.pawapay.PawapayOperationKind;
import java.util.UUID;

/** Publié une seule fois par opération, après commit, sur FAILED ou SUBMIT_REJECTED détecté par le poller. */
public record PawapayOperationFailedEvent(UUID operationId, PawapayOperationKind kind, UUID paymentId,
                                          String failureCode, String failureMessage) {}
