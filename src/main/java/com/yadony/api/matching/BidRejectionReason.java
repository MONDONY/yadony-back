package com.yadony.api.matching;

/**
 * Motifs qu'un voyageur peut donner en refusant une demande (FLUTTER-AF).
 *
 * <p>Une liste fermée plutôt qu'un texte libre : l'expéditeur comprend le refus sans
 * qu'un champ libre serve à échanger un numéro de téléphone hors de l'app. Le code est
 * stocké dans {@code bids.rejection_reason}, et l'app comme la notification le traduisent.
 */
public enum BidRejectionReason {
    NO_CAPACITY,
    CONTENT_NOT_ACCEPTED,
    HANDOVER_NOT_POSSIBLE,
    TRIP_CHANGED,
    OTHER;

    /** Le code reconnu, ou {@code null} pour un motif absent ou inconnu. */
    public static BidRejectionReason parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
