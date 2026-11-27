package com.yadony.api.kyc.provider;

import java.time.LocalDateTime;

/**
 * Vue live d'une session chez son fournisseur, pour le back-office.
 *
 * @param lastStep    derniere etape du parcours atteinte par l'utilisateur ({@code ID_DOCUMENT},
 *                    {@code LIVENESS}, {@code FACE_MATCH}), {@code null} si aucune ou inconnue.
 * @param lastStepFinished {@code false} quand cette etape a ete commencee sans etre terminee.
 * @param unavailable vrai uniquement quand l'appel au fournisseur a echoue — jamais quand il
 *                    n'y a simplement aucune session a interroger, ni quand l'implementation
 *                    du fournisseur n'est plus deployee.
 */
public record ProviderAdminView(String status,
                                String lastErrorCode,
                                String lastErrorReason,
                                LocalDateTime createdAt,
                                boolean unavailable,
                                String lastStep,
                                Boolean lastStepFinished) {

    /** Sans etape connue : fournisseur qui n'expose pas le detail du parcours. */
    public ProviderAdminView(String status, String lastErrorCode, String lastErrorReason,
                             LocalDateTime createdAt, boolean unavailable) {
        this(status, lastErrorCode, lastErrorReason, createdAt, unavailable, null, null);
    }

    public static ProviderAdminView absent() {
        return new ProviderAdminView(null, null, null, null, false);
    }

    /** Le fournisseur n'a pas repondu, ou son implementation n'est plus deployee. */
    public static ProviderAdminView unreachable() {
        return new ProviderAdminView(null, null, null, null, true);
    }
}
