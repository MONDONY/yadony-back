package com.yadony.api.auth;

import com.yadony.api.auth.FirebaseContactService.DeletionOutcome;
import com.yadony.api.auth.FirebaseContactService.PhoneHolder;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Rattache un numéro, dont la possession vient d'être prouvée (code SMS consommé par
 * l'appelant), au compte Firebase d'un utilisateur Yadony (YADONY-FLUTTER-PROD-Y).
 *
 * <p>Firebase Auth refuse un numéro déjà porté par un autre de ses comptes. Or la table
 * {@code users} ne stocke pas le téléphone, et un compte Firebase peut porter un numéro
 * sans compte Yadony derrière lui :
 * <ul>
 *   <li>inscription par SMS abandonnée avant {@code /auth/register} (le compte Firebase
 *       est créé dès que le code est vérifié) ;</li>
 *   <li>compte finalisé (ligne {@code users} anonymisée) dont la suppression Firebase a
 *       échoué.</li>
 * </ul>
 * Ces comptes « fantômes » sont libérés au profit de celui qui prouve posséder le numéro,
 * mais seulement s'ils n'ont aucune autre identité (ni email, ni autre fournisseur) :
 * un compte Firebase lié à une ligne {@code users} non supprimée n'est JAMAIS touché.
 */
@Service
public class PhoneOwnershipService {

    private static final Logger log = LoggerFactory.getLogger(PhoneOwnershipService.class);

    /** Comment le fantôme a été libéré, consigné dans {@code audit_log}. */
    enum ReleaseMethod { FIREBASE_USER_DELETED, PHONE_CLEARED }

    private final FirebaseContactService firebaseContact;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final TransactionTemplate independentTx;

    public PhoneOwnershipService(FirebaseContactService firebaseContact,
                                 UserRepository userRepository,
                                 AuditService auditService,
                                 PlatformTransactionManager transactionManager) {
        this.firebaseContact = firebaseContact;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.independentTx = new TransactionTemplate(transactionManager);
        this.independentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Écrit {@code phoneNumber} sur le compte Firebase de {@code user}, après avoir libéré
     * le numéro d'un compte fantôme s'il y a lieu.
     *
     * @throws YadonyBusinessException 409 {@code phone-pending-deletion} si le numéro
     *         appartient à un compte en cours de suppression, 409 {@code phone-already-exists}
     *         s'il appartient à un vrai compte, 503 si Firebase n'a pas suivi
     */
    public void claimPhone(UserEntity user, String phoneNumber) {
        String selfUid = user.getFirebaseUid();
        Optional<PhoneHolder> holder = firebaseContact.findPhoneHolder(phoneNumber)
                .filter(h -> !h.uid().equals(selfUid));
        boolean reclaimed = false;
        if (holder.isPresent()) {
            releaseOrRefuse(user, holder.get());
            reclaimed = true;
        }
        writePhone(selfUid, phoneNumber, reclaimed);
    }

    private void releaseOrRefuse(UserEntity user, PhoneHolder holder) {
        Optional<UserEntity> holderRow = userRepository.findByFirebaseUidIncludingDeleted(holder.uid());
        if (holderRow.isPresent() && holderRow.get().getDeletedAt() == null) {
            if (holderRow.get().getStatus() == UserStatus.PENDING_DELETION) {
                throw pendingDeletion();
            }
            throw alreadyExists();
        }
        if (!holder.phoneOnly()) {
            // Email ou Google/Apple : quelqu'un s'en sert peut-être pour se connecter.
            throw alreadyExists();
        }

        ReleaseMethod method = release(holder.uid());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("releasedFirebaseUid", holder.uid());
        payload.put("releasedUserId", holderRow.map(UserEntity::getId).orElse(null));
        payload.put("holderAccount", holderRow.isPresent() ? "ANONYMIZED" : "NONE");
        payload.put("releaseMethod", method.name());
        payload.put("verifiedBy", "sms-otp");
        // Transaction indépendante : la libération côté Firebase est irréversible, sa trace
        // doit survivre même si l'écriture qui suit échoue et annule la transaction appelante.
        // Payload sans le numéro (audit_log est immuable).
        independentTx.executeWithoutResult(status -> auditService.log(
                "USER", user.getId(), "USER_PHONE_RECLAIMED", user.getId(), payload));
        log.info("Numéro libéré d'un compte Firebase fantôme {} ({}) au profit de l'utilisateur {}",
                holder.uid(), method, user.getId());
    }

    private ReleaseMethod release(String phantomUid) {
        DeletionOutcome outcome = firebaseContact.deleteAccount(phantomUid);
        if (outcome == DeletionOutcome.DELETED || outcome == DeletionOutcome.ALREADY_GONE) {
            return ReleaseMethod.FIREBASE_USER_DELETED;
        }
        if (firebaseContact.clearPhone(phantomUid)) {
            return ReleaseMethod.PHONE_CLEARED;
        }
        log.error("Compte Firebase fantôme {} : ni suppression ni retrait du numéro", phantomUid);
        throw unavailable("phone-release-failed",
                "Ce numéro n'a pas pu être libéré pour le moment. Réessayez dans un instant.");
    }

    /**
     * Écriture du numéro sur le compte courant. Après une libération, Firebase peut mettre
     * un instant à voir le numéro libre : un second essai, puis une erreur 503 propre. Le
     * compte courant n'est jamais à moitié modifié (l'écriture Firebase est atomique, rien
     * n'est écrit en base avant elle) et le code SMS reste valable pour réessayer.
     */
    private void writePhone(String selfUid, String phoneNumber, boolean reclaimed) {
        try {
            firebaseContact.updatePhone(selfUid, phoneNumber);
        } catch (YadonyBusinessException first) {
            if (!reclaimed) {
                throw first;
            }
            log.warn("Rattachement du numéro libéré à {} : premier essai refusé, nouvel essai", selfUid);
            try {
                firebaseContact.updatePhone(selfUid, phoneNumber);
            } catch (YadonyBusinessException second) {
                throw unavailable("phone-attach-failed",
                        "Ce numéro a été libéré mais n'a pas pu être ajouté à votre compte. "
                                + "Réessayez dans un instant avec le même code.");
            }
        }
    }

    private static YadonyBusinessException pendingDeletion() {
        return new YadonyBusinessException(
                HttpStatus.CONFLICT, "phone-pending-deletion",
                "Phone Number Belongs To An Account Being Deleted",
                "Ce numéro appartient à un compte en cours de suppression. "
                        + "Connectez-vous avec ce numéro pour annuler la suppression.");
    }

    private static YadonyBusinessException alreadyExists() {
        return new YadonyBusinessException(
                HttpStatus.CONFLICT, "phone-already-exists",
                "Phone Number Already Registered",
                "Ce numéro a déjà son propre compte Yadony : connectez-vous avec ce numéro, "
                        + "ou contactez le support pour regrouper vos comptes.");
    }

    private static YadonyBusinessException unavailable(String code, String detail) {
        return new YadonyBusinessException(
                HttpStatus.SERVICE_UNAVAILABLE, code, "Phone Number Not Attached", detail);
    }
}
