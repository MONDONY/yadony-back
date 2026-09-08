package com.yadony.api.payments.pawapay.dto;

import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Projection d'une opération encore ouverte, pour le poller de réconciliation : les quatre
 * seules colonnes qu'il lit. Ni {@code msisdn} ni {@code raw_callback} (toutes deux chiffrées)
 * ne sont hydratées pour chaque ligne du lot.
 */
public record PawapayOpenOperation(UUID id, PawapayOperationKind kind, PawapayOperationStatus status,
                                   LocalDateTime createdAt) {
}
