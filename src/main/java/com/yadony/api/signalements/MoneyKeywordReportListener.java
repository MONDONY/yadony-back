package com.yadony.api.signalements;

import com.yadony.api.common.MoneyKeywordDetector;
import com.yadony.api.matching.events.BidDescriptionSubmittedEvent;
import com.yadony.api.requests.event.PackageRequestDescriptionSubmittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.UUID;

/**
 * Détection automatique d'envoi d'argent (CGU article 4) : quand la description d'un colis ou
 * d'une demande d'expéditeur contient un terme comme « espèces », « cash » ou « chèque », un
 * signalement {@code SYSTEM} rejoint la file de modération. Le colis n'est jamais bloqué : l'app
 * a déjà averti l'expéditeur, l'équipe décide.
 *
 * <p>Après le commit, volontairement : le colis existe forcément, et rien de ce qui se passe ici
 * ne peut annuler son envoi. Toute erreur est journalisée puis avalée, sinon elle remonterait à
 * l'expéditeur dont le colis est pourtant bien enregistré.
 */
@Component
public class MoneyKeywordReportListener {

    private static final Logger log = LoggerFactory.getLogger(MoneyKeywordReportListener.class);

    private final ReportService reportService;

    public MoneyKeywordReportListener(ReportService reportService) {
        this.reportService = reportService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBidDescription(BidDescriptionSubmittedEvent event) {
        flag(ReportTargetType.BID, event.bidId(), event.description());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPackageRequestDescription(PackageRequestDescriptionSubmittedEvent event) {
        flag(ReportTargetType.PACKAGE_REQUEST, event.packageRequestId(), event.description());
    }

    void flag(ReportTargetType targetType, UUID targetId, String description) {
        if (targetId == null) {
            return;
        }
        Optional<String> term = MoneyKeywordDetector.firstMatch(description);
        if (term.isEmpty()) {
            return;
        }
        try {
            reportService.recordMoneyKeywordReport(targetType, targetId, term.get())
                    .ifPresent(report -> log.info("Signalement automatique {} : {} {} (terme « {} »)",
                            report.getId(), targetType, targetId, term.get()));
        } catch (RuntimeException e) {
            log.warn("Signalement automatique non créé pour {} {} : {}", targetType, targetId, e.getMessage());
        }
    }
}
