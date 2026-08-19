package com.yadony.api.admin.broadcast;

import com.yadony.api.notifications.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Envoi d'un broadcast : comptage et historisation synchrones, diffusion asynchrone.
 *
 * <p>Canaux : notification in-app + push FCM via {@code NotificationDispatcher}.
 * <b>Jamais de SMS</b> — le repli SMS est reserve aux notifications critiques
 * ({@code SmsFallbackScheduler}), et un broadcast n'en est pas une.
 *
 * <p>Le type {@code ADMIN_BROADCAST} n'est volontairement PAS mappe dans
 * {@code NotificationPrefsService.TYPE_TO_PREF} : un type inconnu y est autorise par
 * defaut, donc une annonce plateforme atteint tout le monde. C'est un choix — une
 * information de service n'est pas une promotion dont on se desabonne. Le mapper sur
 * {@code pushPromo} laisserait un utilisateur rater une annonce de maintenance.
 */
@Service
public class BroadcastService {

    public static final String NOTIFICATION_TYPE = "ADMIN_BROADCAST";

    private static final Logger log = LoggerFactory.getLogger(BroadcastService.class);

    private final BroadcastAudienceService audienceService;
    private final AdminBroadcastRepository broadcastRepository;
    private final NotificationDispatcher notificationDispatcher;

    public BroadcastService(BroadcastAudienceService audienceService,
                            AdminBroadcastRepository broadcastRepository,
                            NotificationDispatcher notificationDispatcher) {
        this.audienceService = audienceService;
        this.broadcastRepository = broadcastRepository;
        this.notificationDispatcher = notificationDispatcher;
    }

    /** Compte les destinataires et fige la ligne d'historique. Synchrone : la reponse HTTP en depend. */
    @Transactional
    public AdminBroadcastEntity record(String title, String body, BroadcastTarget target, UUID adminId) {
        long recipientCount = audienceService.count(target);
        return broadcastRepository.save(new AdminBroadcastEntity(
                title, body, target.type(), target.origin(), target.destination(),
                target.userId(), (int) recipientCount, adminId));
    }

    /**
     * Diffusion page par page. Chaque destinataire est isole : une erreur FCM ou une
     * ligne de notification en echec ne doit pas priver les suivants du message.
     */
    @Async("broadcastExecutor")
    public void dispatchAsync(UUID broadcastId, String title, String body, BroadcastTarget target) {
        Map<String, String> data = Map.of(
                "type", NOTIFICATION_TYPE,
                "broadcastId", broadcastId.toString());

        int pageNumber = 0;
        int sent = 0;
        int failed = 0;
        Page<UUID> page;
        do {
            page = audienceService.page(target, pageNumber);
            for (UUID userId : page.getContent()) {
                try {
                    notificationDispatcher.notifyUser(userId, title, body, data);
                    sent++;
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("[BROADCAST] {} — echec pour l'utilisateur {} : {}",
                            broadcastId, userId, e.getMessage());
                }
            }
            pageNumber++;
        } while (page.hasNext());

        log.info("[BROADCAST] {} termine — {} envoyes, {} en echec", broadcastId, sent, failed);
    }
}
