package com.yadony.api.admin;

import java.util.Arrays;
import java.util.Optional;

/**
 * Alerte d'un colis, du plus urgent (rang 1) au moins urgent. Le rang est calculé en SQL par
 * {@link AdminBidSql#ALERT_RANK} (0 = aucune alerte) ; un colis n'affiche que la plus urgente.
 */
public enum AdminBidAlert {
    DISPUTE_OPEN(1),
    NO_SHOW(2),
    PAYMENT_PENDING(3),
    LATE(4);

    private final int rank;

    AdminBidAlert(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    /** L'alerte de ce rang, vide pour 0 (aucune alerte) ou un rang inconnu. */
    public static Optional<AdminBidAlert> ofRank(int rank) {
        return Arrays.stream(values()).filter(alert -> alert.rank == rank).findFirst();
    }
}
