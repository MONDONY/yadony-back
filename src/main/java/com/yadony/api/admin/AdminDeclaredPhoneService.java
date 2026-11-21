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
     * Valide le numéro déclaré. Il faut un claim {@code declared_phone} ET une empreinte en base qui
     * correspond à ce claim (sinon 409 {@code declared-phone-missing}) ; un numéro déjà vérifié par SMS
     * n'a rien à valider (409 {@code phone-already-set}). Idempotent : une seconde validation conserve la
     * date et l'administrateur d'origine.
     */
    @Transactional
    public UserEntity verify(UUID userId, UUID adminId) {
        UserEntity user = userRepository.findById(userId).orElseThrow(() ->
                YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.not-found.user"));
        FirebaseContactService.Contact contact = firebaseContact.getContact(user.getFirebaseUid());
        if (contact.phoneNumber() != null && !contact.phoneNumber().isBlank()) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "phone-already-set", "problem.phone-already-set");
        }
        String claim = contact.declaredPhone();
        if (claim == null || claim.isBlank() || !hasher.isConfigured()
                || user.getDeclaredPhoneHash() == null || !user.getDeclaredPhoneHash().equals(hasher.hash(claim))) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT,
                    "declared-phone-missing", "problem.declared-phone-missing");
        }
        if (user.getDeclaredPhoneLevel() == DeclaredPhoneLevel.YADONY_VERIFIED) {
            return user;
        }
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
}
