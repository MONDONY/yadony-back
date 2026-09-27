package com.yadony.api.kyc;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.kyc.events.UserKycActionRequiredEvent;
import com.yadony.api.kyc.events.UserKycRevokedEvent;
import com.yadony.api.kyc.events.UserKycVerifiedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Transitions de statut d'une verification d'identite : les deux enums maintenus en
 * parallele, l'ecriture d'audit, les evenements Spring et les alertes d'administration.
 *
 * <p>Sans fournisseur : les webhooks (Stripe, Didit) ne font que traduire leur charge utile
 * vers ces quatre methodes. C'est ce qui evite que la logique metier soit dupliquee entre
 * deux chemins puis diverge — et c'est ce qui survit au retrait de Stripe Identity.
 *
 * <p>{@code users.kyc_status} et {@code kyc_verifications.status} sont resynchronises a la
 * main a chaque transition : n'en toucher qu'un ferait diverger les sources de verite en
 * silence.
 *
 * <p>Deux familles d'appelants : les webhooks des fournisseurs ({@code mark*}) et la file de
 * revue admin ({@code *ByAdmin}). Une decision d'administrateur negative (refus, revocation)
 * fige la ligne : les {@code mark*} l'ignorent jusqu'a la prochaine session, sinon un webhook
 * rejoue ou une revue tardive chez le fournisseur re-verifierait un compte que l'admin vient
 * de refuser.
 */
@Service
public class KycStatusTransitionService {

    private static final Logger log = LoggerFactory.getLogger(KycStatusTransitionService.class);

    private final KycRepository kycRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final AdminAlertService adminAlert;

    public KycStatusTransitionService(KycRepository kycRepository,
                                      UserRepository userRepository,
                                      AuditService auditService,
                                      ApplicationEventPublisher eventPublisher,
                                      AdminAlertService adminAlert) {
        this.kycRepository = kycRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.adminAlert = adminAlert;
    }

    /** Idempotent : un webhook rejoue ne republie pas l'evenement de verification. */
    public void markVerified(KycVerificationEntity kyc, UserEntity user, String sessionId) {
        if (kyc.getStatus() == KycVerificationStatus.VERIFIED || lockedByAdmin(kyc, "KYC_VERIFIED", sessionId)) {
            return;
        }
        applyVerified(kyc, user);
        auditService.log("kyc_verification", kyc.getId(), "KYC_VERIFIED",
                user.getId(), Map.of("sessionId", sessionId));
        eventPublisher.publishEvent(new UserKycVerifiedEvent(user.getId()));
    }

    /**
     * Validation manuelle d'une identite dont les pieces existent chez le fournisseur. Meme
     * transition et meme evenement que {@link #markVerified} : la notification et les metriques
     * ne distinguent pas l'origine de la verification. L'appelant a deja refuse une ligne
     * VERIFIED.
     */
    public void approveByAdmin(KycVerificationEntity kyc, UserEntity user, UUID adminId, String reason) {
        KycVerificationStatus previous = kyc.getStatus();
        kyc.setRejectionCode(null);
        kyc.setRejectionReason(null);
        recordDecision(kyc, KycDecisionKind.APPROVED, adminId, reason);
        applyVerified(kyc, user);
        auditService.log("kyc_verification", kyc.getId(), "KYC_VERIFIED_BY_ADMIN", adminId,
                decisionPayload(kyc, user, previous, null, reason));
        eventPublisher.publishEvent(new UserKycVerifiedEvent(user.getId()));
    }

    /**
     * Refus manuel. Pas d'alerte {@code KYC_IDENTITY_REJECTED} : elle existe pour signaler a
     * l'administration un refus du fournisseur, pas pour lui renvoyer sa propre decision.
     *
     * <p>{@code rejection_reason} recoit le code et non le motif : la colonne est relue par
     * l'application ({@code GET /kyc/status}), le motif interne reste dans
     * {@code decision_reason}.
     */
    public void rejectByAdmin(KycVerificationEntity kyc, UserEntity user, UUID adminId,
                              String code, String reason) {
        KycVerificationStatus previous = kyc.getStatus();
        recordDecision(kyc, KycDecisionKind.REJECTED, adminId, reason);
        applyRejected(kyc, user, code, code);
        auditService.log("kyc_verification", kyc.getId(), "KYC_REJECTED_BY_ADMIN", adminId,
                decisionPayload(kyc, user, previous, code, reason));
        eventPublisher.publishEvent(new UserKycActionRequiredEvent(user.getId(), code));
    }

    /**
     * Retrait d'une verification acquise : VERIFIED → REJECTED sur les deux statuts. Seul
     * chemin qui retrograde une ligne verifiee ; l'appelant a deja verifie qu'elle l'etait.
     */
    public void revokeByAdmin(KycVerificationEntity kyc, UserEntity user, UUID adminId,
                              String code, String reason) {
        KycVerificationStatus previous = kyc.getStatus();
        recordDecision(kyc, KycDecisionKind.REVOKED, adminId, reason);
        applyRejected(kyc, user, code, code);
        auditService.log("kyc_verification", kyc.getId(), "KYC_REVOKED_BY_ADMIN", adminId,
                decisionPayload(kyc, user, previous, code, reason));
        eventPublisher.publishEvent(new UserKycRevokedEvent(user.getId(), code));
    }

    /** Une ligne verifiee n'est jamais retrogradee : un evenement tardif ne doit rien casser. */
    public void markRejected(KycVerificationEntity kyc, UserEntity user, String sessionId,
                             String code, String reason) {
        if (kyc.getStatus() == KycVerificationStatus.VERIFIED) {
            log.warn("Ignoring rejection for already-VERIFIED session {}", sessionId);
            return;
        }
        if (lockedByAdmin(kyc, "KYC_REJECTED", sessionId)) {
            return;
        }
        applyRejected(kyc, user, code, reason);
        auditService.log("kyc_verification", kyc.getId(), "KYC_REJECTED",
                user.getId(), Map.of("sessionId", sessionId, "reason", reason));
        adminAlert.raise("KYC_IDENTITY_REJECTED",
                "Échec de vérification d'identité pour l'utilisateur " + kyc.getUserId()
                        + " (raison: " + reason + ")",
                Map.of("userId", kyc.getUserId().toString(), "sessionId", sessionId, "reason", reason));
        eventPublisher.publishEvent(new UserKycActionRequiredEvent(user.getId(), code));
    }

    /**
     * Revue manuelle en cours chez le fournisseur : l'utilisateur a fini son parcours, il n'a
     * rien a refaire. Aucun evenement — il n'y a rien a lui demander.
     */
    public void markInReview(KycVerificationEntity kyc, UserEntity user, String sessionId) {
        if (kyc.getStatus() == KycVerificationStatus.VERIFIED || lockedByAdmin(kyc, "KYC_IN_REVIEW", sessionId)) {
            return;
        }
        kyc.setStatus(KycVerificationStatus.PENDING);
        // Premiere date seulement : un webhook rejoue ne doit pas faire reculer la demande
        // dans la file, triee du plus ancien au plus recent.
        if (kyc.getSubmittedAt() == null) {
            kyc.setSubmittedAt(now());
        }
        user.setKycStatus(KycStatus.PENDING);
        kycRepository.save(kyc);
        userRepository.save(user);
        auditService.log("kyc_verification", kyc.getId(), "KYC_IN_REVIEW",
                user.getId(), Map.of("sessionId", sessionId));
    }

    /**
     * Parcours interrompu (abandon, expiration, annulation) : l'utilisateur doit pouvoir
     * recommencer. L'etat obtenu est exactement celui que produisent {@code abandonSession} et
     * le reset administrateur, donc {@code createSession} reprend son chemin nominal.
     *
     * <p>Aucune alerte d'administration : un abandon est le geste le plus courant du parcours
     * (l'utilisateur ferme la webview), et alerter a chaque fois noierait les vraies alertes.
     * Seul un refus explicite alerte.
     */
    public void markRestartable(KycVerificationEntity kyc, UserEntity user, String sessionId,
                                String auditAction) {
        if (kyc.getStatus() == KycVerificationStatus.VERIFIED) {
            log.warn("Ignoring {} for already-VERIFIED session {}", auditAction, sessionId);
            return;
        }
        if (lockedByAdmin(kyc, auditAction, sessionId)) {
            return;
        }
        kyc.setStatus(KycVerificationStatus.PENDING);
        // Parcours termine sans decision : la ligne quitte la file de revue.
        kyc.setSubmittedAt(null);
        user.setKycStatus(KycStatus.NOT_STARTED);
        kycRepository.save(kyc);
        userRepository.save(user);
        auditService.log("kyc_verification", kyc.getId(), auditAction,
                user.getId(), Map.of("sessionId", sessionId));
    }

    private void applyVerified(KycVerificationEntity kyc, UserEntity user) {
        kyc.setStatus(KycVerificationStatus.VERIFIED);
        user.setKycStatus(KycStatus.VERIFIED);
        kycRepository.save(kyc);
        userRepository.save(user);
    }

    private void applyRejected(KycVerificationEntity kyc, UserEntity user, String code, String reason) {
        kyc.setStatus(KycVerificationStatus.REJECTED);
        kyc.setRejectionReason(reason);
        kyc.setRejectionCode(code);
        user.setKycStatus(KycStatus.REJECTED);
        kycRepository.save(kyc);
        userRepository.save(user);
    }

    private static void recordDecision(KycVerificationEntity kyc, KycDecisionKind kind, UUID adminId,
                                       String reason) {
        kyc.setDecisionKind(kind);
        kyc.setDecidedByAdminId(adminId);
        kyc.setDecidedAt(now());
        kyc.setDecisionReason(reason);
    }

    private static Map<String, Object> decisionPayload(KycVerificationEntity kyc, UserEntity user,
                                                       KycVerificationStatus previous, String code,
                                                       String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", user.getId().toString());
        payload.put("sessionId", kyc.getVerificationSessionId() != null ? kyc.getVerificationSessionId() : "");
        payload.put("provider", kyc.getProvider() != null ? kyc.getProvider().name() : "");
        payload.put("previousStatus", previous.name());
        if (code != null) {
            payload.put("code", code);
        }
        payload.put("reason", reason);
        return payload;
    }

    private boolean lockedByAdmin(KycVerificationEntity kyc, String event, String sessionId) {
        if (kyc.isLockedByAdmin()) {
            log.warn("Ignoring {} for session {}: admin decision {} prevails until a new session",
                    event, sessionId, kyc.getDecisionKind());
            return true;
        }
        return false;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
