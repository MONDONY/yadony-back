package com.yadony.api.admin.account;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import java.util.UUID;

/**
 * Security principal for authenticated admin users.
 * Stored as the principal of the UsernamePasswordAuthenticationToken
 * set by FirebaseTokenFilter when an admin token is resolved.
 *
 * Task 5 — FirebaseTokenFilter admin integration
 */
public record AdminPrincipal(
        UUID adminId,
        String email,
        AdminRole role,
        boolean mustChangePassword,
        String firebaseUid
) {

    /**
     * Identifiant de l'administrateur qui agit, pour la trace d'audit.
     *
     * <p>{@code audit_log} est immuable : une trace sans acteur, ou qui désigne la cible à la
     * place de l'administrateur, ne pourra jamais être corrigée. Un appel sans
     * {@link AdminPrincipal} est donc refusé (403) avant toute écriture.
     */
    public static UUID requireAdminId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal.adminId();
        }
        throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                "admin-principal-required", "Admin Principal Required",
                "Authentification administrateur requise");
    }
}
