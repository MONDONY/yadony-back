package com.yadony.api.kyc;

import com.fasterxml.jackson.databind.JsonNode;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.kyc.provider.VerificationProviderKind;
import com.yadony.api.kyc.provider.didit.DiditClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Rattrapage des verifications d'identite Didit restees en attente.
 *
 * <p>Seul un webhook Didit fait avancer une verification. S'il se perd, arrive hors delai ou est
 * ignore, l'utilisateur reste en PENDING sans que personne le sache (plainte d'un utilisateur
 * debloque a la main, 09/11/2026). Ce job relit chez Didit l'etat des sessions ouvertes depuis
 * plus de {@code min-age} et applique le meme effet que le webhook ({@link KycDiditStatus}).
 *
 * <p>Il leve aussi une alerte d'administration, une par utilisateur, quand une verification reste
 * bloquee : revue manuelle Didit en attente depuis trop longtemps, ou session que Didit ne
 * repond pas.
 *
 * <p>Idempotent : l'appel Didit se fait HORS transaction (timeout 10 s, ne pas tenir une
 * connexion), puis la ligne est relue en transaction et n'est modifiee que si elle est encore
 * PENDING sur la meme session. Un webhook concurrent gagne toujours.
 */
@Component
public class KycPendingReconciler {

    private static final Logger log = LoggerFactory.getLogger(KycPendingReconciler.class);

    /** {@code admin_alerts.type} est limite a 60 caracteres : prefixe (12) + UUID (36). */
    static final String ALERT_PREFIX = "KYC_PENDING_";
    private static final String IN_REVIEW = "in review";

    private final KycRepository kycRepository;
    private final UserRepository userRepository;
    private final KycStatusTransitionService transitions;
    private final DiditClient didit;
    private final AdminAlertEscalator alerts;
    private final TransactionOperations transactionOperations;

    @Value("${yadony.kyc.pending-reconciliation.enabled:true}") private boolean enabled;
    @Value("${yadony.kyc.pending-reconciliation.min-age:PT30M}") private Duration minAge;
    @Value("${yadony.kyc.pending-reconciliation.max-age:P3D}") private Duration maxAge;
    @Value("${yadony.kyc.pending-reconciliation.batch-size:20}") private int batchSize;
    @Value("${yadony.kyc.pending-reconciliation.review-alert-after:PT2H}") private Duration reviewAlertAfter;
    @Value("${yadony.kyc.pending-reconciliation.unreadable-alert-after:PT2H}") private Duration unreadableAlertAfter;

    public KycPendingReconciler(KycRepository kycRepository, UserRepository userRepository,
                                KycStatusTransitionService transitions, DiditClient didit,
                                AdminAlertEscalator alerts, TransactionOperations transactionOperations) {
        this.kycRepository = kycRepository;
        this.userRepository = userRepository;
        this.transitions = transitions;
        this.didit = didit;
        this.alerts = alerts;
        this.transactionOperations = transactionOperations;
    }

    @Scheduled(cron = "${yadony.kyc.pending-reconciliation.cron:0 */10 * * * *}")
    public void run() {
        if (!enabled) {
            return;
        }
        try {
            reconcileSessions();
        } catch (RuntimeException e) {
            log.error("Rattrapage KYC Didit en échec", e);
        }
        try {
            alertStuckReviews();
        } catch (RuntimeException e) {
            log.error("Alerte KYC en revue Didit en échec", e);
        }
    }

    /** @return le nombre de verifications dont l'etat a ete rattrape. */
    int reconcileSessions() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<KycVerificationEntity> candidates = kycRepository.findStalePending(
                VerificationProviderKind.DIDIT, now.minus(minAge), now.minus(maxAge),
                PageRequest.of(0, batchSize));
        int caughtUp = 0;
        for (KycVerificationEntity candidate : candidates) {
            try {
                if (reconcile(candidate, now)) {
                    caughtUp++;
                }
            } catch (RuntimeException e) {
                log.warn("Rattrapage KYC impossible pour la vérification {} ({})",
                        candidate.getId(), e.getClass().getSimpleName());
            }
        }
        return caughtUp;
    }

    private boolean reconcile(KycVerificationEntity candidate, LocalDateTime now) {
        String sessionId = candidate.getVerificationSessionId();
        Optional<JsonNode> decision = didit.retrieveDecision(sessionId);
        if (decision.isEmpty()) {
            if (candidate.getUpdatedAt() != null
                    && candidate.getUpdatedAt().isBefore(now.minus(unreadableAlertAfter))) {
                raiseStuck(candidate, "Didit ne répond pas pour cette session depuis plus de "
                        + unreadableAlertAfter.toHours() + " h");
            }
            return false;
        }
        JsonNode node = decision.get();
        String status = KycDiditStatus.text(node, "status");
        if (!KycDiditStatus.isFinal(status) && !IN_REVIEW.equals(lower(status))) {
            return false; // parcours encore en cours chez l'utilisateur
        }
        UUID id = candidate.getId();
        boolean applied = Boolean.TRUE.equals(transactionOperations.execute(
                tx -> applyIfStillPending(id, sessionId, status, node)));
        if (applied) {
            log.info("Vérification KYC {} rattrapée : statut Didit « {} »", id, status);
            if (KycDiditStatus.isFinal(status)) {
                resolveAlert(candidate.getUserId());
            }
        }
        return applied;
    }

    private boolean applyIfStillPending(UUID id, String sessionId, String status, JsonNode node) {
        KycVerificationEntity kyc = kycRepository.findById(id).orElse(null);
        if (kyc == null || kyc.getStatus() != KycVerificationStatus.PENDING
                || !sessionId.equals(kyc.getVerificationSessionId())) {
            return false; // un webhook est passe entre-temps
        }
        if (IN_REVIEW.equals(lower(status)) && kyc.getSubmittedAt() != null) {
            return false; // deja en revue : ne pas rejouer l'audit a chaque passage
        }
        UserEntity user = userRepository.findById(kyc.getUserId()).orElse(null);
        if (user == null) {
            return false;
        }
        KycDiditStatus.apply(transitions, status, node, kyc, user, sessionId);
        return true;
    }

    int alertStuckReviews() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<KycVerificationEntity> stuck = kycRepository.findInReviewSince(
                VerificationProviderKind.DIDIT, now.minus(reviewAlertAfter), PageRequest.of(0, 50));
        for (KycVerificationEntity kyc : stuck) {
            raiseStuck(kyc, "Revue manuelle Didit en attente depuis plus de "
                    + reviewAlertAfter.toHours() + " h");
        }
        return stuck.size();
    }

    private void raiseStuck(KycVerificationEntity kyc, String reason) {
        try {
            alerts.raiseOnce(alertType(kyc.getUserId()), "WARN",
                    reason + ". Ouvrir la session dans la console Didit, ou débloquer depuis la file KYC "
                            + "du back-office (utilisateur " + kyc.getUserId() + ").",
                    Map.<String, Object>of("userId", kyc.getUserId().toString(),
                            "sessionId", String.valueOf(kyc.getVerificationSessionId()),
                            "reason", reason));
        } catch (RuntimeException e) {
            log.error("Alerte KYC bloquée impossible à lever pour {}", kyc.getUserId(), e);
        }
    }

    private void resolveAlert(UUID userId) {
        try {
            alerts.resolveOpen(alertType(userId));
        } catch (RuntimeException e) {
            log.warn("Alerte KYC bloquée impossible à clore pour {}", userId, e);
        }
    }

    static String alertType(UUID userId) {
        return ALERT_PREFIX + userId;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
