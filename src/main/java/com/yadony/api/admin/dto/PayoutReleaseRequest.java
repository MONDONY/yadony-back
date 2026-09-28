package com.yadony.api.admin.dto;

/**
 * Corps FACULTATIF de {@code POST /admin/payments/{id}/force-release} et de
 * {@code POST /admin/payments/{id}/mobile-money/retry-payout}.
 *
 * <p>Sans corps (ou {@code overrideHold} absent / {@code false}) : comportement historique, mais
 * refus 409 si le beneficiaire est gele ({@code payout-beneficiary-held}) ou le paiement en
 * litige ({@code payment-disputed}). Avec {@code overrideHold = true}, l'administrateur deroge a
 * ces deux gardes ; {@code overrideReason} est alors obligatoire (10 a 500 caracteres apres
 * suppression des espaces de bord, sinon 422 {@code override-reason-invalid}) et la derogation est
 * auditee ({@code PAYOUT_HOLD_OVERRIDDEN_BY_ADMIN}).
 */
public record PayoutReleaseRequest(Boolean overrideHold, String overrideReason) {

    public static final int REASON_MIN = 10;
    public static final int REASON_MAX = 500;

    public boolean override() {
        return Boolean.TRUE.equals(overrideHold);
    }

    /** Motif sans espaces de bord ; {@code null} si absent. */
    public String trimmedReason() {
        return overrideReason == null ? null : overrideReason.strip();
    }
}
