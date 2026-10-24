package com.yadony.api.admin;

import java.util.Arrays;
import java.util.Optional;

/**
 * Alerte unique d'un paiement (la plus urgente l'emporte). Le rang est celui de
 * {@link AdminPaymentSql#ALERT_RANK} ; 0 signifie « aucune alerte ».
 */
public enum AdminPaymentAlert {
    HELD(1), CHARGEBACK_OPEN(2), MM_FAILED(3), ESCROW_J48(4);

    private final int rank;

    AdminPaymentAlert(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public static Optional<AdminPaymentAlert> ofRank(int rank) {
        return Arrays.stream(values()).filter(a -> a.rank == rank).findFirst();
    }
}
