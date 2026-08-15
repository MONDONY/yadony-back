package com.yadony.api.notifications;

import com.yadony.api.auth.UserDeviceEntity;
import com.yadony.api.auth.UserDeviceJpaRepository;
import com.yadony.api.auth.UserRepository;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class FcmService {

    private static final Logger log = LoggerFactory.getLogger(FcmService.class);

    private final UserRepository userRepository;
    private final UserDeviceJpaRepository userDeviceRepository;
    private final NotificationPrefsService notificationPrefsService;

    public FcmService(UserRepository userRepository,
                      UserDeviceJpaRepository userDeviceRepository,
                      NotificationPrefsService notificationPrefsService) {
        this.userRepository = userRepository;
        this.userDeviceRepository = userDeviceRepository;
        this.notificationPrefsService = notificationPrefsService;
    }

    /**
     * Send a push notification to a user by userId.
     * Returns true if the message was dispatched, false if the user has no token or prefs block it.
     */
    @Transactional
    public boolean sendToUser(UUID userId, String title, String body, Map<String, String> data) {
        String notifType = data != null ? data.get("type") : null;
        if (!notificationPrefsService.isAllowed(userId, notifType)) {
            log.debug("[FCM] Notification supprimée par pref user={} type={}", userId, notifType);
            return false;
        }
        return userRepository.findById(userId)
                .map(user -> {
                    // Un utilisateur peut être connecté sur plusieurs appareils (iPhone +
                    // Android). users.fcm_token ne retient que le dernier inscrit : s'y
                    // limiter rendrait muets tous les autres appareils. On diffuse donc à
                    // tous les jetons connus, user_devices faisant foi.
                    Set<String> tokens = collectTokens(userId, user.getFcmToken());
                    if (tokens.isEmpty()) {
                        log.debug("[FCM] User {} has no FCM token — skipping", userId);
                        return false;
                    }
                    boolean anySent = false;
                    for (String token : tokens) {
                        anySent |= sendToToken(token, title, body, data, userId);
                    }
                    return anySent;
                })
                .orElseGet(() -> {
                    log.warn("[FCM] User {} not found", userId);
                    return false;
                });
    }

    /** Jetons distincts de tous les appareils de l'utilisateur, le plus récent d'abord. */
    private Set<String> collectTokens(UUID userId, String legacyToken) {
        Set<String> tokens = new LinkedHashSet<>();
        for (UserDeviceEntity device : userDeviceRepository.findByUserIdOrderByLastSeenAtDesc(userId)) {
            String token = device.getFcmToken();
            if (token != null && !token.isBlank()) {
                tokens.add(token);
            }
        }
        // Comptes antérieurs à user_devices, ou appareil dont la ligne manque.
        if (legacyToken != null && !legacyToken.isBlank()) {
            tokens.add(legacyToken);
        }
        return tokens;
    }

    public void sendToTopic(String topic, String title, String body) {
        Message message = Message.builder()
                .setTopic(topic)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(AndroidNotification.builder()
                                .setChannelId("dony_general")
                                .setSound("default")
                                .build())
                        .build())
                .build();

        try {
            String messageId = FirebaseMessaging.getInstance().send(message);
            log.info("[FCM] Topic '{}' → messageId={}", topic, messageId);
        } catch (FirebaseMessagingException e) {
            log.error("[FCM] Topic '{}' send failed: {}", topic, e.getMessage(), e);
        }
    }

    private boolean sendToToken(String token, String title, String body,
                                Map<String, String> data, UUID userId) {
        boolean isCritical = data != null && NotificationTypes.isCritical(data.get("type"));
        Message.Builder builder = Message.builder()
                .setToken(token)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(AndroidNotification.builder()
                                .setChannelId("dony_transactional")
                                .setSound("default")
                                .build())
                        .build())
                .setApnsConfig(ApnsConfig.builder()
                        .setAps(Aps.builder()
                                .setSound("default")
                                .setBadge(1)
                                // Les notifications critiques attendent un ACK sous 60 s,
                                // faute de quoi SmsFallbackScheduler envoie un SMS. Sans
                                // content-available, iOS ne réveille pas l'application tant
                                // que l'utilisateur n'ouvre pas la notification : l'ACK ne
                                // pourrait jamais partir depuis l'arrière-plan et le SMS
                                // serait envoyé à chaque fois, alors que le push est arrivé.
                                // Réservé aux critiques : c'est le seul cas qui justifie de
                                // réveiller le téléphone, et iOS bride ces réveils.
                                .setContentAvailable(isCritical)
                                .build())
                        .build());

        if (data != null && !data.isEmpty()) {
            builder.putAllData(data);
        }

        try {
            String messageId = FirebaseMessaging.getInstance().send(builder.build());
            log.info("[FCM] Sent to user={} messageId={}", userId, messageId);
            return true;
        } catch (FirebaseMessagingException e) {
            if ("UNREGISTERED".equals(e.getMessagingErrorCode() != null
                    ? e.getMessagingErrorCode().name() : "")) {
                log.warn("[FCM] Token UNREGISTERED for user={} — clearing that device", userId);
                clearToken(userId, token);
            } else {
                log.error("[FCM] Send failed for user={}: {}", userId, e.getMessage(), e);
            }
            return false;
        }
    }

    /**
     * Retire un jeton devenu invalide. Seul l'appareil concerné est purgé : effacer
     * users.fcm_token systématiquement couperait les autres appareils du compte.
     */
    @Transactional
    protected void clearToken(UUID userId, String staleToken) {
        List<UserDeviceEntity> stale = new ArrayList<>();
        for (UserDeviceEntity device : userDeviceRepository.findByUserIdOrderByLastSeenAtDesc(userId)) {
            if (staleToken.equals(device.getFcmToken())) {
                stale.add(device);
            }
        }
        userDeviceRepository.deleteAll(stale);

        userRepository.findById(userId).ifPresent(user -> {
            if (staleToken.equals(user.getFcmToken())) {
                user.setFcmToken(null);
                userRepository.save(user);
            }
        });
    }
}
