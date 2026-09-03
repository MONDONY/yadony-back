package com.yadony.api.automation;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.notifications.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Toutes les 15 minutes, vérifie pour chaque voyageur ayant la règle
 * "alert_capacity_free" active si l'une de ses annonces a retrouvé assez de
 * capacité (freedKgThreshold) depuis assez longtemps (consecutiveHours), et
 * notifie une seule fois par fenêtre de libération.
 */
@Component
public class CapacityWatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(CapacityWatchScheduler.class);

    private final AutomationRuleRepository ruleRepository;
    private final AnnouncementRepository announcementRepository;
    private final AutomationCapacityWatermarkRepository watermarkRepository;
    private final NotificationDispatcher notificationDispatcher;
    private final AutomationActionExecutor executor;

    public CapacityWatchScheduler(AutomationRuleRepository ruleRepository,
                                  AnnouncementRepository announcementRepository,
                                  AutomationCapacityWatermarkRepository watermarkRepository,
                                  NotificationDispatcher notificationDispatcher,
                                  AutomationActionExecutor executor) {
        this.ruleRepository = ruleRepository;
        this.announcementRepository = announcementRepository;
        this.watermarkRepository = watermarkRepository;
        this.notificationDispatcher = notificationDispatcher;
        this.executor = executor;
    }

    /**
     * {@code @Transactional} ici est sûr : contrairement à
     * {@code AutomationActionExecutor.tryExecuteBidAction}, cette méthode
     * n'invoque jamais de méthode {@code @Transactional} de {@code BidService}
     * (ni directement, ni via {@code executor.recordNotification}, qui se
     * contente d'un write d'historique). Il n'y a donc pas de risque de
     * transaction participante partagée avec un appelant externe dont un
     * rollback marquerait à tort cette transaction "rollback-only" — on peut
     * englober lecture + upsert du watermark dans une seule transaction pour
     * la cohérence.
     */
    @Scheduled(fixedRate = 15 * 60 * 1000)
    @Transactional
    public void run() {
        List<UUID> travelerIds = ruleRepository.findAll().stream()
                .filter(r -> "alert_capacity_free".equals(r.getPresetRuleId()) && r.isEnabled())
                .map(AutomationRuleEntity::getTravelerId)
                .distinct()
                .toList();
        checkCapacityWatermarks(travelerIds);
    }

    void checkCapacityWatermarks(List<UUID> travelerIds) {
        for (UUID travelerId : travelerIds) {
            AutomationRuleEntity rule = ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId)
                    .stream()
                    .filter(r -> "alert_capacity_free".equals(r.getPresetRuleId()) && r.isEnabled())
                    .findFirst()
                    .orElse(null);
            if (rule == null) continue;

            BigDecimal freedKgThreshold = configNumber(rule, "freedKgThreshold", new BigDecimal("5"));
            int consecutiveHours = configInt(rule, "consecutiveHours", 2);

            for (AnnouncementEntity announcement : announcementRepository.findActiveByTravelerId(travelerId)) {
                evaluateAnnouncement(rule, announcement, freedKgThreshold, consecutiveHours);
            }
        }
    }

    private void evaluateAnnouncement(AutomationRuleEntity rule, AnnouncementEntity announcement,
                                      BigDecimal freedKgThreshold, int consecutiveHours) {
        UUID announcementId = announcement.getId();
        AutomationCapacityWatermarkEntity watermark = watermarkRepository.findByAnnouncementId(announcementId)
                .orElseGet(() -> {
                    AutomationCapacityWatermarkEntity w = new AutomationCapacityWatermarkEntity();
                    w.setAnnouncementId(announcementId);
                    return w;
                });

        boolean aboveThreshold = announcement.getAvailableKg() != null
                && announcement.getAvailableKg().compareTo(freedKgThreshold) >= 0;

        if (!aboveThreshold) {
            if (watermark.getFreeSince() != null || watermark.getLastAlertedAt() != null) {
                watermark.setFreeSince(null);
                watermark.setLastAlertedAt(null);
                watermarkRepository.save(watermark);
            }
            return;
        }

        if (watermark.getFreeSince() == null) {
            watermark.setFreeSince(OffsetDateTime.now());
            watermarkRepository.save(watermark);
            return;
        }

        boolean heldLongEnough =
                Duration.between(watermark.getFreeSince(), OffsetDateTime.now()).toHours() >= consecutiveHours;
        boolean alreadyAlertedThisWindow =
                watermark.getLastAlertedAt() != null && !watermark.getLastAlertedAt().isBefore(watermark.getFreeSince());

        if (heldLongEnough && !alreadyAlertedThisWindow) {
            var text = com.yadony.api.notifications.NotificationTexts.capacityFree(
                    announcement.getAvailableKg(), consecutiveHours,
                    announcement.getDepartureCity(), announcement.getArrivalCity());
            notificationDispatcher.notifyUser(rule.getTravelerId(), text.title(), text.body(),
                    Map.of("type", "automation_capacity_free", "announcementId", announcementId.toString()));
            watermark.setLastAlertedAt(OffsetDateTime.now());
            watermarkRepository.save(watermark);
            executor.recordNotification(rule, rule.getTravelerId(), "ALERT_CAPACITY_FREE");
        }
    }

    private BigDecimal configNumber(AutomationRuleEntity rule, String key, BigDecimal fallback) {
        Object v = rule.getAction() != null ? rule.getAction().get(key) : null;
        if (v == null) return fallback;
        return new BigDecimal(v.toString());
    }

    private int configInt(AutomationRuleEntity rule, String key, int fallback) {
        Object v = rule.getAction() != null ? rule.getAction().get(key) : null;
        if (v == null) return fallback;
        return Integer.parseInt(v.toString());
    }
}
