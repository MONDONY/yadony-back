package com.yadony.api.admin;

import com.yadony.api.auth.DeclaredPhoneHasher;
import com.yadony.api.auth.DeclaredPhoneLevel;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.PhoneLookupService;
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
    private final PhoneLookupService phoneLookup;

    public AdminDeclaredPhoneService(UserRepository userRepository, FirebaseContactService firebaseContact,
                                     DeclaredPhoneHasher hasher, AuditService auditService,
                                     PhoneLookupService phoneLookup) {
        this.phoneLookup = phoneLookup;
        this.userRepository = userRepository;
        this.firebaseContact = firebaseContact;
        this.hasher = hasher;
        this.auditService = auditService;
    }

    /**
     * Valide le numéro déclaré que l'administrateur a sous les yeux ({@code shownPhone}). Lecture Firebase
     * fraîche (pas le cache de 5 min) : il faut un claim {@code declared_phone} ET une empreinte en base
     * qui correspond à ce claim (sinon 409 {@code declared-phone-missing}) ; le numéro affiché doit être
     * encore celui du claim et de l'empreinte (sinon 409 {@code declared-phone-changed} : le compte a
     * redéclaré entre-temps) ; un numéro déjà vérifié par SMS ou validé sur un autre compte est refusé
     * (409 {@code declared-phone-conflict}). Un numéro SMS sur ce compte : 409 {@code phone-already-set}.
     * Idempotent : une seconde validation conserve la date et l'administrateur d'origine.
     */
    @Transactional
    public UserEntity verify(UUID userId, UUID adminId, String shownPhone) {
        UserEntity user = userRepository.findById(userId).orElseThrow(() ->
                YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.not-found.user"));
        FirebaseContactService.Contact contact = firebaseContact.getFreshContact(user.getFirebaseUid());
        if (contact.phoneNumber() != null && !contact.phoneNumber().isBlank()) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "phone-already-set", "problem.phone-already-set");
        }
        String claim = contact.declaredPhone();
        if (claim == null || claim.isBlank() || !hasher.isConfigured() || user.getDeclaredPhoneHash() == null) {
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
     * Refuse si un AUTRE compte détient déjà ce numéro : propriétaire vérifié par SMS (il n'a pas
     * l'empreinte du déclaré) ou compte déjà validé par Yadony. Un simple déclarant ne bloque pas.
     */
    private void requireNoOtherOwner(UserEntity user, String claim, String claimHash) {
        for (UserEntity other : phoneLookup.findAllByPhone(claim)) {
            if (other.getId() != null && other.getId().equals(user.getId())) {
                continue;
            }
            boolean smsOwner = !claimHash.equals(other.getDeclaredPhoneHash());
            if (smsOwner || other.getDeclaredPhoneLevel() == DeclaredPhoneLevel.YADONY_VERIFIED) {
                throw YadonyBusinessException.localized(HttpStatus.CONFLICT,
                        "declared-phone-conflict", "problem.declared-phone-conflict");
            }
        }
    }
}
