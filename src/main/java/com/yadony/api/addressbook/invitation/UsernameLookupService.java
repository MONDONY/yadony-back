package com.yadony.api.addressbook.invitation;

import com.yadony.api.addressbook.invitation.dto.UsernameLookupResponse;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.auth.UsernamePolicy;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Recherche d'un compte par identifiant. L'aperçu rendu se limite à l'identifiant et à l'initiale
 * du prénom ; inconnu, supprimé, format invalide et bloqué donnent la même réponse (404), pour que
 * rien ne trahisse l'existence d'un compte ni un blocage.
 */
@Service
public class UsernameLookupService {

    private final UserRepository userRepository;
    private final BlockVisibility blockVisibility;
    private final LookupRateLimiter rateLimiter;

    public UsernameLookupService(UserRepository userRepository, BlockVisibility blockVisibility,
                                 @Qualifier("lookupRateLimiter") LookupRateLimiter rateLimiter) {
        this.userRepository = userRepository;
        this.blockVisibility = blockVisibility;
        this.rateLimiter = rateLimiter;
    }

    @Transactional(readOnly = true)
    public UsernameLookupResponse lookup(String firebaseUid, String raw) {
        UserEntity me = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> YadonyNotFoundException.localized("problem.kyc.user-not-found"));
        beginLookup(me.getId(), raw);
        UserEntity found = resolveVisible(me, raw).orElseThrow(UsernameLookupService::notFound);
        if (found.getId().equals(me.getId())) {
            throw selfInvitation();
        }
        return new UsernameLookupResponse(found.getUsername(), initial(found));
    }

    /**
     * Point d'entrée unique d'une recherche, à appeler avant toute résolution : un format invalide donne 404 sans
     * toucher ni la base ni le plafond ; sinon la recherche est comptée dans le plafond horaire de l'utilisateur
     * (429 au-delà), inconnus compris, ce qui borne l'énumération. Seau unique partagé entre l'aperçu et l'envoi.
     */
    public void beginLookup(UUID userId, String raw) {
        if (!UsernamePolicy.isWellFormed(UsernamePolicy.normalize(raw))) {
            throw notFound();
        }
        if (!rateLimiter.tryAcquire(userId)) {
            throw YadonyBusinessException.localized(HttpStatus.TOO_MANY_REQUESTS, "recipient-lookup-quota",
                    "problem.recipient-lookup-quota");
        }
    }

    /**
     * Compte visible de {@code caller} derrière {@code raw}, vide si le format est invalide, si le
     * compte n'existe pas (ou est supprimé), est suspendu ou banni, ou est masqué par un blocage. Peut rendre
     * l'appelant lui-même : à l'appelant de refuser ({@link #selfInvitation()}).
     */
    public Optional<UserEntity> resolveVisible(UserEntity caller, String raw) {
        String normalized = UsernamePolicy.normalize(raw);
        // Seul le format compte : les mots réservés empêchent de créer un identifiant, pas d'en retrouver un existant.
        if (!UsernamePolicy.isWellFormed(normalized)) {
            return Optional.empty();
        }
        return userRepository.findByUsernameIgnoreCase(normalized.toLowerCase(Locale.ROOT))
                .filter(u -> u.getStatus() != UserStatus.SUSPENDED && u.getStatus() != UserStatus.BANNED)
                .filter(u -> !blockVisibility.isHidden(caller.getId(), u.getId()));
    }

    public static YadonyBusinessException notFound() {
        return YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found");
    }

    public static YadonyBusinessException selfInvitation() {
        return YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "recipient-invitation-self",
                "problem.recipient-invitation-self");
    }

    private static String initial(UserEntity user) {
        String first = user.getFirstName();
        if (first == null || first.isBlank()) {
            return "?";
        }
        String stripped = first.strip();
        return String.valueOf(Character.toChars(stripped.codePointAt(0))).toUpperCase(Locale.ROOT);
    }
}
