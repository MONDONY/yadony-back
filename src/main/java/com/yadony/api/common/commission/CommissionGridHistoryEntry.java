package com.yadony.api.common.commission;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Ligne de l'historique des grilles, annulées comprises.
 *
 * @param grid        la grille.
 * @param cancelledAt date d'annulation ({@code deleted_at}) ; {@code null} pour une grille conservée.
 * @param createdBy   administrateur qui l'a programmée.
 * @param note        commentaire saisi à la programmation.
 * @param createdAt   date de programmation.
 */
public record CommissionGridHistoryEntry(CommissionGrid grid, LocalDateTime cancelledAt, UUID createdBy,
                                         String note, LocalDateTime createdAt) {
}
