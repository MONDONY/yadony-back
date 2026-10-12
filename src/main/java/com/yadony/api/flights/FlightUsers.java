package com.yadony.api.flights;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Identité de l'appelant pour le package vols : identifiant et langue d'affichage.
 *
 * <p>Un visiteur (session Firebase anonyme, {@code ROLE_GUEST}) peut chercher et réserver sans
 * compte. Il est volontairement rendu <b>anonyme</b> ({@link Caller#id()} nul) même s'il a
 * déjà une ligne {@code users} née d'un favori : ses recherches ne sont pas mémorisées et
 * rien de ce package ne référence cette ligne, que la purge des invités supprime
 * physiquement. Sa langue vient de l'en-tête {@code Accept-Language}.
 */
@Component
public class FlightUsers {

    public record Caller(UUID id, String language) {

        /** Vrai pour un visiteur sans compte : rien ne doit être enregistré à son nom. */
        public boolean anonymous() {
            return id == null;
        }
    }

    private final UserRepository userRepository;

    public FlightUsers(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Caller resolve(String firebaseUid) {
        if (isGuest()) {
            return new Caller(null, "en".equalsIgnoreCase(LocaleContextHolder.getLocale().getLanguage()) ? "en" : "fr");
        }
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyNotFoundException("User not found"));
        return new Caller(user.getId(), user.getPreferredLanguage().code());
    }

    /** Comme {@link #resolve}, pour une action qui exige un vrai compte (alertes). */
    public Caller resolveAccount(String firebaseUid) {
        Caller caller = resolve(firebaseUid);
        if (caller.anonymous()) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "flights-account-required",
                    "Account Required", "Créez un compte pour utiliser les alertes prix.");
        }
        return caller;
    }

    private static boolean isGuest() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_GUEST".equals(a.getAuthority()));
    }
}
