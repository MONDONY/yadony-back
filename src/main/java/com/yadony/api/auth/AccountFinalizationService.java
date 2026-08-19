package com.yadony.api.auth;

import com.yadony.api.auth.events.AccountDeletionRequestedEvent;
import com.yadony.api.auth.events.UserFinalizedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.kyc.KycRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountFinalizationService {

    private static final Logger log = LoggerFactory.getLogger(AccountFinalizationService.class);

    private final UserRepository userRepository;
    private final KycRepository kycRepository;
    private final StorageService storageService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final FirebaseContactService firebaseContact;

    public AccountFinalizationService(UserRepository userRepository,
                                      KycRepository kycRepository,
                                      StorageService storageService,
                                      ApplicationEventPublisher eventPublisher,
                                      AuditService auditService,
                                      FirebaseContactService firebaseContact) {
        this.userRepository = userRepository;
        this.kycRepository = kycRepository;
        this.storageService = storageService;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.firebaseContact = firebaseContact;
    }

    @Transactional
    public void finalize(UserEntity user, FinalizationReason reason) {
        UUID userId = user.getId();
        String uid = userId.toString();

        // 1. Pseuyadonymise personal data
        // Téléphone et email ne sont plus stockés ici : ils disparaissent avec le
        // compte Firebase supprimé à l'étape 5.
        user.setFirstName("Utilisateur");
        user.setLastName("supprimé");
        user.setBirthDate(null);
        user.setCity(null);
        user.setFcmToken(null);
        // Lot C — le SIRET est un identifiant d'entreprise reel : chiffre au repos, mais
        // parfaitement re-identifiant tant qu'il subsiste. Le laisser vidait l'anonymisation
        // de son sens pour tout compte PRO.
        user.setProSiret(null);
        // Lot C — un statut KYC VERIFIED survivant a la suppression rattache le compte
        // anonymise a une verification d'identite reelle.
        user.setKycStatus(KycStatus.NOT_STARTED);
        // Lot C — la demande est traitee : la conserver garde une trace datee de l'acte,
        // et fausse toute file de demandes RGPD en attente (cf. reactivateAccount qui, lui,
        // l'efface deja).
        user.setDeletionRequestedAt(null);
        user.setStatus(UserStatus.BANNED);
        user.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
        userRepository.save(user);

        // 2. Soft-delete KYC
        kycRepository.findByUserId(userId).ifPresent(kyc -> {
            // Lot C — le soft-delete seul laissait intact le pointeur de session Stripe, qui
            // mene aux pieces d'identite detenues par Stripe : la suppression du compte
            // laissait donc un chemin d'acces vivant vers les documents de l'utilisateur.
            kyc.setStripeVerificationSessionId(null);
            kyc.setRejectionReason(null);
            kyc.setRejectionCode(null);
            kyc.softDelete();
            kycRepository.save(kyc);
        });

        // 3. Delete Cloudflare R2 files
        storageService.deleteByPrefix("kyc/" + userId + "/");

        // 4. Publish events → cross-package cleanup
        // AccountDeletionRequestedEvent : nécessaire ici car les chemins HARD_IMMEDIATE et
        // SOFT_GRACE_EXPIRED n'ont jamais forcément transité par requestDeletion() (seul autre
        // émetteur de cet event). Idempotent par construction : matching.AccountDeletionListener
        // (bids où ce user est sender) et cancellation.AccountDeletionCancellationListener (ses
        // annonces de voyageur) ne cancel-lent que ce qui est encore ACTIVE/FULL/ouvert — un
        // second déclenchement (ex. déjà traité via requestDeletion) ne trouve plus rien à faire.
        eventPublisher.publishEvent(new AccountDeletionRequestedEvent(userId));
        eventPublisher.publishEvent(new UserFinalizedEvent(userId, reason));

        // 5. Delete Firebase user (porteur du téléphone et de l'email)
        firebaseContact.deleteAccount(user.getFirebaseUid());

        // 6. Immutable audit entry
        auditService.log("USER", userId, "USER_GDPR_DELETION", userId,
                Map.of("reason", reason.name(), "pseuyadonymized", true));
        log.info("Account finalized for user {} (reason: {})", uid, reason);
    }
}
