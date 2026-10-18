package com.yadony.api.payments.bankpayout;

import java.time.LocalDate;

/**
 * Statut du virement bancaire montré à l'app ({@code bankPayout.status} de « Mon argent »).
 * Contrat figé avec l'app : ne pas renommer.
 */
public enum BankPayoutStatus {
    /** Payé sur le compte Stripe du voyageur, pas encore disponible ({@code availableOn} futur ou inconnu). */
    PENDING_AVAILABILITY,
    /** Disponible sur le compte Stripe, aucun virement ne le couvre encore. */
    AVAILABLE,
    /** Virement vers la banque créé ou en route ({@code arrivalDate} = date prévue). */
    IN_TRANSIT,
    /** Arrivé sur la banque. */
    PAID,
    /** Virement refusé par la banque ({@code failureCode}). */
    FAILED;

    /**
     * @param payoutStatus statut brut Stripe du dernier payout connu, {@code null} si aucun
     * @param availableOn  date de disponibilité sur le compte Connect, {@code null} si inconnue
     * @param today        jour courant en UTC
     */
    public static BankPayoutStatus derive(String payoutStatus, LocalDate availableOn, LocalDate today) {
        if (payoutStatus != null) {
            switch (payoutStatus) {
                case "pending", "in_transit" -> { return IN_TRANSIT; }
                case "paid" -> { return PAID; }
                case "failed" -> { return FAILED; }
                default -> { /* canceled : l'argent revient sur le solde Stripe */ }
            }
        }
        if (availableOn == null || availableOn.isAfter(today)) {
            return PENDING_AVAILABILITY;
        }
        return AVAILABLE;
    }
}
