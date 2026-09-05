package com.yadony.api.kyc;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.kyc.dto.KycAdminStatusResponse;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.kyc.provider.IdentityProviderResolver;
import com.yadony.api.kyc.provider.ProviderAdminView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * KYC côté administrateur — délibérément distinct de {@link KycService}.
 *
 * <p>{@code KycService} est keyé sur {@code String firebaseUid} (self-service mobile), alors
 * que toute route {@code /admin/**} est keyée sur {@code UUID userId}. Réutiliser ses
 * méthodes imposerait une résolution supplémentaire à chaque appel ; ce service travaille
 * directement en UUID via {@link KycRepository#findByUserId(UUID)}.
 */
@Service
public class KycAdminService {

    private static final Logger log = LoggerFactory.getLogger(KycAdminService.class);

    /** Rendu quand aucune ligne KYC n'existe : l'enum de kyc_schema n'a pas de NOT_STARTED. */
    private static final String NOT_STARTED = "NOT_STARTED";

    private final KycRepository kycRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationDispatcher notificationDispatcher;
    private final IdentityProviderResolver providers;

    public KycAdminService(KycRepository kycRepository,
                           UserRepository userRepository,
                           AuditService auditService,
                           NotificationDispatcher notificationDispatcher,
                           IdentityProviderResolver providers) {
        this.kycRepository = kycRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.notificationDispatcher = notificationDispatcher;
        this.providers = providers;
    }

    @Transactional(readOnly = true)
    public KycAdminStatusResponse getForUser(UUID userId) {
        UserEntity user = requireUser(userId);
        Optional<KycVerificationEntity> kyc = kycRepository.findByUserId(userId);
        String sessionId = kyc.map(KycVerificationEntity::getVerificationSessionId).orElse(null);

        // Vue live chez le fournisseur de la LIGNE. Implementation retiree ou fournisseur
        // injoignable : la vue est marquee indisponible, jamais une 5xx renvoyee a l'admin.
        ProviderAdminView view = sessionId == null
                ? ProviderAdminView.absent()
                : kyc.flatMap(k -> providers.forRecord(k.getProvider()))
                        .map(provider -> provider.fetchAdminView(sessionId))
                        .orElseGet(ProviderAdminView::unreachable);

        return new KycAdminStatusResponse(
                userId,
                user.getKycStatus().name(),
                kyc.map(k -> k.getStatus().name()).orElse(NOT_STARTED),
                kyc.map(KycVerificationEntity::getRejectionReason).orElse(null),
                kyc.map(KycVerificationEntity::getRejectionCode).orElse(null),
                sessionId,
                view.status(),
                view.lastErrorCode(),
                view.lastErrorReason(),
                view.createdAt(),
                view.unavailable(),
                kyc.map(k -> k.getProvider().name()).orElse(null)
        );
    }

    /**
     * Réinitialise le KYC d'un utilisateur : abandonne la session en cours chez son fournisseur
     * (best-effort), puis remet la ligne locale à zéro <strong>par UPDATE en place</strong>.
     *
     * <p>Jamais de soft-delete suivi d'une recréation : {@code uq_kyc_user_id}
     * ({@code V2__init_kyc_schema.sql:19}) est une contrainte UNIQUE classique, sans
     * {@code WHERE deleted_at IS NULL} — la ligne soft-deletée resterait physiquement
     * présente et l'insertion suivante violerait la contrainte.
     *
     * <p>L'état obtenu ({@code users.kyc_status = NOT_STARTED} + ligne conservée en
     * {@code PENDING} sans session) est exactement celui que produit déjà
     * {@code KycService.abandonSession} : {@code createSession} reprend ensuite le chemin
     * {@code NOT_STARTED} et réécrit la ligne existante.
     */
    @Transactional
    public KycAdminStatusResponse resetForUser(UUID userId, UUID adminId, String reason) {
        UserEntity user = requireUser(userId);
        KycVerificationEntity kyc = kycRepository.findByUserId(userId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "kyc-not-started", "Unprocessable",
                        "Cet utilisateur n'a jamais démarré de vérification d'identité"));

        String previousSessionId = kyc.getVerificationSessionId();
        KycVerificationStatus previousStatus = kyc.getStatus();

        // Best-effort : une session injoignable ou déjà terminée ne doit jamais bloquer la
        // remise à zéro locale (même politique que KycService.abandonSession).
        if (previousSessionId != null) {
            providers.forRecord(kyc.getProvider())
                    .ifPresent(provider -> provider.abandonSession(previousSessionId));
        }

        kyc.setStatus(KycVerificationStatus.PENDING);
        kyc.setVerificationSessionId(null);
        kyc.setRejectionReason(null);
        kyc.setRejectionCode(null);
        kycRepository.save(kyc);

        // Les deux enums sont resynchronisés à la main à chaque transition : n'en toucher
        // qu'un ferait diverger les sources de vérité en silence.
        user.setKycStatus(KycStatus.NOT_STARTED);
        userRepository.save(user);

        auditService.log("kyc_verification", kyc.getId(), "KYC_RESET_BY_ADMIN", adminId,
                Map.of("userId", userId.toString(),
                        "reason", reason != null ? reason : "",
                        "previousStatus", previousStatus.name(),
                        "previousSessionId", previousSessionId != null ? previousSessionId : ""));

        var text = com.yadony.api.notifications.NotificationTexts.kycReset();
        notificationDispatcher.notifyUser(userId, text.title(), text.body(), Map.of("type", "KYC_RESET"));

        log.info("KYC reset for user {} by admin {}", userId, adminId);

        // `provider` est conservé : la colonne est NOT NULL, et la valeur devient sans objet
        // dès que la session est effacée — elle sera réécrite au prochain createSession.
        return new KycAdminStatusResponse(
                userId, KycStatus.NOT_STARTED.name(), KycVerificationStatus.PENDING.name(),
                null, null, null, null, null, null, null, false, kyc.getProvider().name());
    }

    private UserEntity requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "Not Found", "Utilisateur introuvable"));
    }

}
