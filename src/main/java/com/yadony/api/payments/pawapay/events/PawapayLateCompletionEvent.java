package com.yadony.api.payments.pawapay.events;

import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationPurpose;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * pawaPay annonce COMPLETED pour une opération que Yadony avait déjà classée en échec
 * ({@code FAILED}, {@code SUBMIT_REJECTED}). La transition est refusée (le statut final reste), mais
 * l'argent a réellement bougé chez pawaPay : un dépôt encaissé sans crédit du portefeuille, ou un
 * remboursement versé alors qu'un repli (versement direct) a pu partir aussi. Publié par
 * {@code PawapayOperationService#apply} ; seule une vérification humaine tranche.
 */
public record PawapayLateCompletionEvent(UUID operationId, PawapayOperationKind kind,
                                         PawapayOperationPurpose purpose, PawapayOperationStatus previousStatus,
                                         UUID paymentId, UUID userId, BigDecimal amount, String currency) {}
