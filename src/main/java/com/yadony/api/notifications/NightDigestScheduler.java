package com.yadony.api.notifications;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Récapitulatif du matin : un seul push pour tout ce que les heures calmes ont retenu
 * ({@link PushQuietHours}), envoyé dès que la nuit est finie à l'heure locale de chacun.
 * Pas de ligne de feed : les notifications retenues y sont déjà.
 */
@Component
public class NightDigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(NightDigestScheduler.class);

    private final PushQuietHours quietHours;
    private final UserRepository userRepository;
    private final FcmService fcmService;
    private final NotificationDispatcher notificationDispatcher;

    public NightDigestScheduler(PushQuietHours quietHours, UserRepository userRepository,
                                FcmService fcmService, NotificationDispatcher notificationDispatcher) {
        this.quietHours = quietHours;
        this.userRepository = userRepository;
        this.fcmService = fcmService;
        this.notificationDispatcher = notificationDispatcher;
    }

    @Scheduled(cron = "${yadony.notifications.quiet-hours.digest-cron:0 */15 * * * *}")
    public void sendMorningDigests() {
        for (UUID userId : quietHours.usersWithHeldPushes()) {
            try {
                UserEntity user = userRepository.findById(userId).orElse(null);
                if (user == null) {
                    quietHours.takeHeld(userId);
                    continue;
                }
                if (!quietHours.isMorning(user)) {
                    continue;
                }
                int held = quietHours.takeHeld(userId);
                if (held <= 0) {
                    continue;
                }
                var text = NotificationTexts.nightDigest(notificationDispatcher.messagesFor(userId), held);
                fcmService.sendToUser(userId, text.title(), text.body(),
                        Map.of("type", PushQuietHours.NIGHT_DIGEST_TYPE, "count", String.valueOf(held)));
            } catch (RuntimeException e) {
                log.warn("[FCM] Récapitulatif du matin impossible pour {} : {}", userId, e.getClass().getSimpleName());
            }
        }
    }
}
