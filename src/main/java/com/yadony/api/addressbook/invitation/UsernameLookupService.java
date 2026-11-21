package com.yadony.api.addressbook.invitation;

import com.yadony.api.addressbook.invitation.dto.UsernameLookupResponse;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UsernamePolicy;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

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
                                 LookupRateLimiter rateLimiter) {
        this.userRepository = userRepository;
        this.blockVisibility = blockVisibility;
        this.rateLimiter = rateLimiter;
    }

    @Transactional(readOnly = true)
    public UsernameLookupResponse lookup(String firebaseUid, String raw) {
        UserEntity me = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> YadonyNotFoundException.localized("problem.kyc.user-not-found"));
        // Compté avant toute recherche, inconnus compris : c'est ce qui borne l'énumération.
        if (!rateLimiter.tryAcquire(me.getId())) {
            throw YadonyBusinessException.localized(HttpStatus.TOO_MANY_REQUESTS, "recipient-lookup-quota",
                    "problem.recipient-lookup-quota");
        }
        UserEntity found = resolveVisible(me, raw).orElseThrow(UsernameLookupService::notFound);
        if (found.getId().equals(me.getId())) {
            throw selfInvitation();
        }
        return new UsernameLookupResponse(found.getUsername(), initial(found));
    }

    /**
     * Compte visible de {@code caller} derrière {@code raw}, vide si le format est invalide, si le
     * compte n'existe pas (ou est supprimé) ou s'il est masqué par un blocage. Peut rendre
     * l'appelant lui-même : à l'appelant de refuser ({@link #selfInvitation()}).
     */
    Optional<UserEntity> resolveVisible(UserEntity caller, String raw) {
        String normalized = UsernamePolicy.normalize(raw);
        // Seul le format compte : les mots réservés empêchent de créer un identifiant, pas d'en retrouver un existant.
        if (UsernamePolicy.violation(normalized).filter(v -> v == UsernamePolicy.Violation.INVALID_FORMAT).isPresent()) {
            return Optional.empty();
        }
        return userRepository.findByUsernameIgnoreCase(normalized.toLowerCase(Locale.ROOT))
                .filter(u -> !blockVisibility.isHidden(caller.getId(), u.getId()));
    }

    static YadonyBusinessException notFound() {
        return YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found");
    }

    static YadonyBusinessException selfInvitation() {
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
