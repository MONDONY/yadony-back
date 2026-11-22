package com.yadony.api.admin;

import com.yadony.api.auth.DeclaredPhoneHasher;
import com.yadony.api.auth.DeclaredPhoneLevel;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.dao.DataIntegrityViolationException;
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

    /** Index unique partiel de V398 : un seul compte vivant validé par Yadony par empreinte. */
    static final String YADONY_UNIQUE_INDEX = "uq_users_declared_phone_yadony";

    /**
     * Valide le numéro déclaré que l'administrateur a sous les yeux ({@code shownPhone}). Sans clé
     * d'empreinte ou sans Firebase : 503 {@code declared-phone-unavailable}. Lecture Firebase stricte
     * (ni le cache de 5 min, ni {@code Contact.EMPTY} sur une panne : une lecture ratée répond 503
     * {@code declared-phone-unavailable}, pas « aucun numéro ») : il faut un claim {@code declared_phone}
     * ET une empreinte en base qui correspond à ce claim (sinon 409 {@code declared-phone-missing}) ; le
     * numéro affiché doit être encore celui du claim et de l'empreinte (sinon 409
     * {@code declared-phone-changed} : le compte a redéclaré entre-temps). Un numéro SMS sur ce compte :
     * 409 {@code phone-already-set}. Le contrôle des autres comptes échoue fermé (voir
     * {@link #requireNoOtherOwner}) ; deux validations simultanées du même numéro sur deux comptes sont
     * départagées par l'index {@value #YADONY_UNIQUE_INDEX} : la seconde reçoit 409
     * {@code declared-phone-conflict}. Idempotent : une seconde validation du même compte conserve la date
     * et l'administrateur d'origine.
     */
    @Transactional
    public UserEntity verify(UUID userId, UUID adminId, String shownPhone) {
        UserEntity user = userRepository.findById(userId).orElseThrow(() ->
                YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.not-found.user"));
        if (!hasher.isConfigured() || !firebaseContact.isAvailable()) {
            throw unavailable();
        }
        FirebaseContactService.Contact contact = firebaseContact.readContactStrict(user.getFirebaseUid())
                .orElseThrow(AdminDeclaredPhoneService::unavailable);
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
        // Flush avant toute autre écriture : un échec de la base (version, index unique) ne laisse aucune
        // trace d'audit.
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, YADONY_UNIQUE_INDEX)) {
                // Un autre administrateur vient de valider ce numéro sur un autre compte.
                throw conflict();
            }
            throw e;
        }
        // Payload sans PII : ni numéro ni empreinte.
        auditService.log("USER", user.getId(), "ADMIN_USER_PHONE_VERIFIED", adminId,
                Map.of("level", DeclaredPhoneLevel.YADONY_VERIFIED.name()));
        return user;
    }

    /**
     * Refuse si un AUTRE compte détient déjà ce numéro. Échec fermé, sans lecture groupée des claims :
     * <ul>
     *   <li>propriétaire vérifié par SMS : recherche Firebase directe. Panne ou quota ({@code ERROR}) =
     *       503 {@code declared-phone-unavailable}, jamais « aucun propriétaire ». Un autre UID avec un compte
     *       Yadony vivant = 409 {@code declared-phone-conflict}, qu'il ait gardé l'empreinte d'une ancienne
     *       déclaration ou non. Un autre UID SANS compte Yadony vivant (compte Firebase fantôme : ancienne
     *       tentative de connexion par téléphone, compte supprimé ou anonymisé) = 409
     *       {@code declared-phone-held-by-unregistered-account} : toujours refusé, rien n'est libéré
     *       automatiquement, mais l'administrateur sait que le blocage vient de Firebase et non d'un autre
     *       membre. Ce compte lui-même = 409 {@code phone-already-set} (rattachement SMS entre les deux
     *       lectures) ;</li>
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
            // @Where : un compte supprimé ou anonymisé compte comme absent.
            if (owner.uid() == null || userRepository.findByFirebaseUid(owner.uid()).isEmpty()) {
                throw YadonyBusinessException.localized(HttpStatus.CONFLICT,
                        "declared-phone-held-by-unregistered-account",
                        "problem.declared-phone-held-by-unregistered-account");
            }
            throw conflict();
        }
        if (userRepository.existsByDeclaredPhoneHashAndDeclaredPhoneLevelAndIdNot(
                claimHash, DeclaredPhoneLevel.YADONY_VERIFIED, user.getId())) {
            throw conflict();
        }
    }

    /** Vrai si la violation vient de cette contrainte : les autres ne doivent pas passer pour un conflit. */
    private static boolean violates(DataIntegrityViolationException e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(constraint)) {
                return true;
            }
        }
        return false;
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
