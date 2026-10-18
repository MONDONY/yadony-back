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
     * Attribut de requête posé par FirebaseTokenFilter quand l'appelant a un compte admin
     * actif mais appelle une route de l'application (hors back-office). Il n'y reçoit aucun
     * droit admin ; la marque ne sert qu'à le laisser passer pendant la maintenance.
     */
    public static final String ADMIN_ACCOUNT_ATTRIBUTE = "yadony.adminAccount";

    /**
     * Vrai si l'appelant est un administrateur : AdminPrincipal sur le back-office, ou
     * compte admin marqué par le filtre sur une route de l'application.
     */
    public static boolean isAdminCaller(Authentication authentication,
                                        jakarta.servlet.http.HttpServletRequest request) {
        if (authentication != null && authentication.getPrincipal() instanceof AdminPrincipal) {
            return true;
        }
        return request != null && Boolean.TRUE.equals(request.getAttribute(ADMIN_ACCOUNT_ATTRIBUTE));
    }

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
