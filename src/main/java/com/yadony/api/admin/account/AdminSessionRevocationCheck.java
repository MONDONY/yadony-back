package com.yadony.api.admin.account;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Révocation des sessions admin, vérifiée sur le back-office.
 *
 * <p>{@code verifyIdToken(token)} ne contrôle que la signature et l'expiration : un jeton émis
 * avant {@code revokeRefreshTokens} reste accepté jusqu'à son expiration (environ 1 h). Or la
 * réinitialisation du mot de passe d'un admin par un super admin, réponse type à un compte
 * compromis, laisse l'admin ACTIVE et ne compte que sur cette révocation : sans ce contrôle,
 * le jeton volé passait encore, jusqu'à {@code /admin/me/change-password} (audit sécu 21/10).
 *
 * <p>{@code verifyIdToken(token, true)} interroge Firebase à chaque appel : le résultat
 * positif est gardé 60 s par jeton, une révocation prend donc effet en une minute au plus,
 * et tout de suite pour les révocations faites par ce back ({@link #forgetVerifiedSessions}).
 */
@Component
public class AdminSessionRevocationCheck {

    private static final Logger log = LoggerFactory.getLogger(AdminSessionRevocationCheck.class);

    public enum Result { VALID, REVOKED, UNAVAILABLE }

    private final Cache<String, Boolean> verified = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(1_000)
            .build();

    public Result check(String idToken) {
        if (verified.getIfPresent(idToken) != null) {
            return Result.VALID;
        }
        try {
            FirebaseAuth.getInstance().verifyIdToken(idToken, true);
            verified.put(idToken, Boolean.TRUE);
            return Result.VALID;
        } catch (FirebaseAuthException e) {
            AuthErrorCode code = e.getAuthErrorCode();
            if (code == AuthErrorCode.REVOKED_ID_TOKEN || code == AuthErrorCode.USER_DISABLED
                    || code == AuthErrorCode.USER_NOT_FOUND || code == AuthErrorCode.EXPIRED_ID_TOKEN) {
                return Result.REVOKED;
            }
            log.warn("Admin session revocation check unavailable: {}", code);
            return Result.UNAVAILABLE;
        }
    }

    /** Après une révocation faite ici : plus aucun jeton gardé en cache n'est cru sur parole. */
    public void forgetVerifiedSessions() {
        verified.invalidateAll();
    }
}
