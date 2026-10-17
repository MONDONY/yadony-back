package com.yadony.api.gppro;

public enum GpProApplicationStatus {
    AWAITING_KYC, PENDING, APPROVED, REJECTED, CANCELLED;

    /** Demande encore en cours : une seule par personne. */
    public boolean isOpen() {
        return this == AWAITING_KYC || this == PENDING;
    }
}
