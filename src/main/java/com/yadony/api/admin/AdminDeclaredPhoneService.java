package com.yadony.api.admin;

import com.yadony.api.auth.DeclaredPhoneHasher;
import com.yadony.api.auth.DeclaredPhoneLevel;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Validation à la main, par un administrateur, du numéro qu'un compte a déclaré sans SMS.
 *
 * <p>Le niveau {@code YADONY_VERIFIED} ne sert qu'à rattacher les colis et résoudre les invitations :
 * le champ Firebase {@code phoneNumber} n'est jamais écrit (connexion et paiements l'ignorent donc).
 */
@Service
public class AdminDeclaredPhoneService {

    private final UserRepository userRepository;
    private final FirebaseContactService firebaseContact;
    private final DeclaredPhoneHasher hasher;
    private final AuditService auditService;

    public AdminDeclaredPhoneService(UserRepository userRepository, FirebaseContactService firebaseContact,
                                     DeclaredPhoneHasher hasher, AuditService auditService) {
        this.userRepository = userRepository;
        this.firebaseContact = firebaseContact;
        this.hasher = hasher;
        this.auditService = auditService;
    }

    /**
     * Valide le numéro déclaré que l'administrateur a sous les yeux ({@code shownPhone}). Sans clé
     * d'empreinte ou sans Firebase : 503 {@code declared-phone-unavailable}. Lecture Firebase fraîche
     * (pas le cache de 5 min) : il faut un claim {@code declared_phone} ET une empreinte en base qui
     * correspond à ce claim (sinon 409 {@code declared-phone-missing}) ; le numéro affiché doit être
     * encore celui du claim et de l'empreinte (sinon 409 {@code declared-phone-changed} : le compte a
     * redéclaré entre-temps). Un numéro SMS sur ce compte : 409 {@code phone-already-set}. Le contrôle
     * des autres comptes échoue fermé (voir {@link #requireNoOtherOwner}). Idempotent : une seconde
     * validation conserve la date et l'administrateur d'origine.
     */
    @Transactional
    public UserEntity verify(UUID userId, UUID adminId, String shownPhone) {
        UserEntity user = userRepository.findById(userId).orElseThrow(() ->
                YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.not-found.user"));
        if (!hasher.isConfigured() || !firebaseContact.isAvailable()) {
            throw unavailable();
        }
        FirebaseContactService.Contact contact = firebaseContact.getFreshContact(user.getFirebaseUid());
        if (contact.phoneNumber() != null && !contact.phoneNumber().isBlank()) {
            throw phoneAlreadySet();
        }
        String claim = contact.declaredPhone();
        if (claim == null || claim.isBlank() || user.getDeclaredPhoneHash() == null) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT,
                    "declared-phone-missing", "problem.declared-phone-missing");
        }
        String claimHash = hasher.hash(claim);
        if (!user.getDeclaredPhoneHash().equals(claimHash)) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT,
                    "declared-phone-missing", "problem.declared-phone-missing");
        }
        if (shownPhone == null || !shownPhone.equals(claim)) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT,
                    "declared-phone-changed", "problem.declared-phone-changed");
        }
        if (user.getDeclaredPhoneLevel() == DeclaredPhoneLevel.YADONY_VERIFIED) {
            return user;
        }
        requireNoOtherOwner(user, claim, claimHash);
        user.setDeclaredPhoneLevel(DeclaredPhoneLevel.YADONY_VERIFIED);
        user.setDeclaredPhoneVerifiedAt(LocalDateTime.now(ZoneOffset.UTC));
        user.setDeclaredPhoneVerifiedBy(adminId);
        // Flush avant toute autre écriture : un échec de la base (version) ne laisse aucune trace d'audit.
        userRepository.saveAndFlush(user);
        // Payload sans PII : ni numéro ni empreinte.
        auditService.log("USER", user.getId(), "ADMIN_USER_PHONE_VERIFIED", adminId,
                Map.of("level", DeclaredPhoneLevel.YADONY_VERIFIED.name()));
        return user;
    }

    /**
     * Refuse si un AUTRE compte détient déjà ce numéro. Échec fermé, sans lecture groupée des claims :
     * <ul>
     *   <li>propriétaire vérifié par SMS : recherche Firebase directe. Panne ou quota ({@code ERROR}) =
     *       503 {@code declared-phone-unavailable}, jamais « aucun propriétaire ». Un autre UID = 409
     *       {@code declared-phone-conflict}, qu'il ait gardé l'empreinte d'une ancienne déclaration ou non.
     *       Ce compte lui-même = 409 {@code phone-already-set} (rattachement SMS entre les deux lectures) ;</li>
     *   <li>compte déjà validé par Yadony : lu en base sur l'empreinte, sans plafond ni confirmation par le
     *       claim (une lecture Firebase en échec ne le fait donc pas disparaître). Un simple déclarant ne
     *       bloque pas.</li>
     * </ul>
     */
    private void requireNoOtherOwner(UserEntity user, String claim, String claimHash) {
        FirebaseContactService.PhoneOwnerLookup owner = firebaseContact.findPhoneOwner(claim);
        if (owner == null || owner.outcome() == FirebaseContactService.PhoneOwnerLookup.Outcome.ERROR) {
            throw unavailable();
        }
        if (owner.outcome() == FirebaseContactService.PhoneOwnerLookup.Outcome.FOUND) {
            if (owner.uid() != null && owner.uid().equals(user.getFirebaseUid())) {
                throw phoneAlreadySet();
            }
            throw conflict();
        }
        if (userRepository.existsByDeclaredPhoneHashAndDeclaredPhoneLevelAndIdNot(
                claimHash, DeclaredPhoneLevel.YADONY_VERIFIED, user.getId())) {
            throw conflict();
        }
    }

    private static YadonyBusinessException unavailable() {
        return YadonyBusinessException.localized(HttpStatus.SERVICE_UNAVAILABLE,
                "declared-phone-unavailable", "problem.declared-phone-unavailable");
    }

    private static YadonyBusinessException phoneAlreadySet() {
        return YadonyBusinessException.localized(HttpStatus.CONFLICT, "phone-already-set", "problem.phone-already-set");
    }

    private static YadonyBusinessException conflict() {
        return YadonyBusinessException.localized(HttpStatus.CONFLICT,
                "declared-phone-conflict", "problem.declared-phone-conflict");
    }
}
