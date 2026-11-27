package com.yadony.api.kyc;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.admin.AdminAlertRepository;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Alerte d'administration quand un utilisateur quitte sa verification d'identite en cours de
 * route, pour que le support puisse le relancer. Une alerte par utilisateur
 * ({@code KYC_ABANDONED_<userId>}), close d'elle-meme des qu'il n'est plus dans cet etat
 * (nouvelle session, soumission, validation, refus ou reset administrateur).
 *
 * <p>Detection par balayage plutot que dans les chemins d'abandon (webview fermee, webhook
 * Didit, reset) : un seul endroit couvre tous les cas, et le delai {@code min-idle} evite
 * d'alerter pour un aller-retour de quelques secondes.
 */
@Component
public class KycAbandonedAlerter {

    private static final Logger log = LoggerFactory.getLogger(KycAbandonedAlerter.class);

    /** {@code admin_alerts.type} est limite a 60 caracteres : prefixe (14) + UUID (36). */
    static final String ALERT_PREFIX = "KYC_ABANDONED_";

    private final KycRepository kycRepository;
    private final UserRepository userRepository;
    private final AdminAlertRepository alertRepository;
    private final AdminAlertEscalator alerts;

    @Value("${yadony.kyc.abandoned-alert.enabled:true}") private boolean enabled;
    @Value("${yadony.kyc.abandoned-alert.min-idle:PT30M}") private Duration minIdle;
    @Value("${yadony.kyc.abandoned-alert.max-age:P7D}") private Duration maxAge;
    @Value("${yadony.kyc.abandoned-alert.batch-size:50}") private int batchSize;

    public KycAbandonedAlerter(KycRepository kycRepository, UserRepository userRepository,
                               AdminAlertRepository alertRepository, AdminAlertEscalator alerts) {
        this.kycRepository = kycRepository;
        this.userRepository = userRepository;
        this.alertRepository = alertRepository;
        this.alerts = alerts;
    }

    @Scheduled(cron = "${yadony.kyc.abandoned-alert.cron:0 */10 * * * *}")
    public void run() {
        if (!enabled) {
            return;
        }
        try {
            raiseAbandoned();
        } catch (RuntimeException e) {
            log.error("Alerte KYC abandonné en échec", e);
        }
        try {
            resolveRecovered();
        } catch (RuntimeException e) {
            log.error("Clôture des alertes KYC abandonné en échec", e);
        }
    }

    /** @return le nombre d'alertes levees. */
    int raiseAbandoned() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int raised = 0;
        for (KycVerificationEntity kyc : kycRepository.findAbandonedBefore(
                now.minus(minIdle), PageRequest.of(0, batchSize))) {
            // Au-dela de max-age la relance n'a plus de sens : ne pas ressusciter l'historique.
            if (kyc.getUpdatedAt() != null && kyc.getUpdatedAt().isBefore(now.minus(maxAge))) {
                continue;
            }
            try {
                if (alerts.raiseOnce(alertType(kyc.getUserId()), "INFO",
                        "Un utilisateur a quitté sa vérification d'identité avant la fin. "
                                + "À relancer depuis la file KYC, onglet « Abandonnées » (utilisateur "
                                + kyc.getUserId() + ").",
                        Map.<String, Object>of("userId", kyc.getUserId().toString(),
                                "sessionId", String.valueOf(kyc.getVerificationSessionId())))) {
                    raised++;
                }
            } catch (RuntimeException e) {
                log.error("Alerte KYC abandonné impossible à lever pour {}", kyc.getUserId(), e);
            }
        }
        return raised;
    }

    /** @return le nombre d'alertes closes. */
    int resolveRecovered() {
        int resolved = 0;
        List<AdminAlertEntity> open = alertRepository.findOpenReferencing(ALERT_PREFIX + "%", 500);
        for (AdminAlertEntity alert : open) {
            if (!alert.getType().startsWith(ALERT_PREFIX)) {
                continue;
            }
            UUID userId;
            try {
                userId = UUID.fromString(alert.getType().substring(ALERT_PREFIX.length()));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!stillAbandoned(userId)) {
                resolved += alerts.resolveOpen(alert.getType());
            }
        }
        return resolved;
    }

    private boolean stillAbandoned(UUID userId) {
        return kycRepository.findByUserId(userId).map(kyc ->
                kyc.getStatus() == KycVerificationStatus.PENDING
                        && kyc.getVerificationSessionId() != null
                        && kyc.getSubmittedAt() == null
                        && userRepository.findById(userId)
                                .map(u -> u.getKycStatus() == KycStatus.NOT_STARTED)
                                .orElse(false))
                .orElse(false);
    }

    static String alertType(UUID userId) {
        return ALERT_PREFIX + userId;
    }
}
