package com.yadony.api.kyc;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.kyc.events.UserKycActionRequiredEvent;
import com.yadony.api.kyc.events.UserKycVerifiedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;

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
        if (kyc.getStatus() == KycVerificationStatus.VERIFIED) {
            return;
        }
        kyc.setStatus(KycVerificationStatus.VERIFIED);
        user.setKycStatus(KycStatus.VERIFIED);
        kycRepository.save(kyc);
        userRepository.save(user);
        auditService.log("kyc_verification", kyc.getId(), "KYC_VERIFIED",
                user.getId(), Map.of("sessionId", sessionId));
        eventPublisher.publishEvent(new UserKycVerifiedEvent(user.getId()));
    }

    /** Une ligne verifiee n'est jamais retrogradee : un evenement tardif ne doit rien casser. */
    public void markRejected(KycVerificationEntity kyc, UserEntity user, String sessionId,
                             String code, String reason) {
        if (kyc.getStatus() == KycVerificationStatus.VERIFIED) {
            log.warn("Ignoring rejection for already-VERIFIED session {}", sessionId);
            return;
        }
        kyc.setStatus(KycVerificationStatus.REJECTED);
        kyc.setRejectionReason(reason);
        kyc.setRejectionCode(code);
        user.setKycStatus(KycStatus.REJECTED);
        kycRepository.save(kyc);
        userRepository.save(user);
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
        if (kyc.getStatus() == KycVerificationStatus.VERIFIED) {
            return;
        }
        kyc.setStatus(KycVerificationStatus.PENDING);
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
        kyc.setStatus(KycVerificationStatus.PENDING);
        user.setKycStatus(KycStatus.NOT_STARTED);
        kycRepository.save(kyc);
        userRepository.save(user);
        auditService.log("kyc_verification", kyc.getId(), auditAction,
                user.getId(), Map.of("sessionId", sessionId));
    }
}
