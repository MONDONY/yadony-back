package com.yadony.api.payments.pawapay.dto;

/** Résultat immédiat (synchrone) d'une initiation de dépôt/versement/remboursement pawaPay v2. */
public record PawapayInitiationResult(Outcome outcome, String failureCode, String failureMessage) {

    public enum Outcome { ACCEPTED, REJECTED, DUPLICATE_IGNORED }

    public static PawapayInitiationResult accepted() {
        return new PawapayInitiationResult(Outcome.ACCEPTED, null, null);
    }
}
