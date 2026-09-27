package com.yadony.api.kyc;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Emails des administrateurs, pour signer les decisions et l'historique de la file KYC.
 *
 * <p>Port implemente dans {@code admin/account} : {@code kyc/} ne depend pas des comptes
 * d'administration, c'est l'inverse.
 */
public interface AdminEmailDirectory {

    /** Administrateurs connus parmi {@code adminIds} ; un compte supprime est simplement absent. */
    Map<UUID, String> emailsOf(Collection<UUID> adminIds);
}
