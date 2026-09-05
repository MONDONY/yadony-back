package com.yadony.api.kyc.provider;

import java.time.LocalDateTime;

/**
 * Vue live d'une session chez son fournisseur, pour le back-office.
 *
 * @param unavailable vrai uniquement quand l'appel au fournisseur a echoue — jamais quand il
 *                    n'y a simplement aucune session a interroger, ni quand l'implementation
 *                    du fournisseur n'est plus deployee.
 */
public record ProviderAdminView(String status,
                                String lastErrorCode,
                                String lastErrorReason,
                                LocalDateTime createdAt,
                                boolean unavailable) {

    public static ProviderAdminView absent() {
        return new ProviderAdminView(null, null, null, null, false);
    }

    /** Le fournisseur n'a pas repondu, ou son implementation n'est plus deployee. */
    public static ProviderAdminView unreachable() {
        return new ProviderAdminView(null, null, null, null, true);
    }
}
