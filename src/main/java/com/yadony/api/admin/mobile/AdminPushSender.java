package com.yadony.api.admin.mobile;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Envoi FCM vers un appareil admin (PWA, Web Push). Distinct de {@code FcmService}, qui vise les
 * utilisateurs de l'app. Non final : les tests le remplacent par un faux, sans Firebase.
 */
@Component
public class AdminPushSender {

    public enum SendResult { OK, INVALID_TOKEN, ERROR }

    private static final Logger log = LoggerFactory.getLogger(AdminPushSender.class);

    public SendResult send(String fcmToken, String title, String body, Map<String, String> data) {
        try {
            FirebaseMessaging.getInstance().send(buildMessage(fcmToken, title, body, data));
            return SendResult.OK;
        } catch (FirebaseMessagingException e) {
            SendResult result = mapError(e.getMessagingErrorCode());
            if (result == SendResult.INVALID_TOKEN) {
                log.warn("[AdminPush] Jeton non enregistré, appareil retiré");
            } else {
                log.error("[AdminPush] Envoi échoué : {}", e.getMessage());
            }
            return result;
        } catch (RuntimeException e) {
            // Firebase non initialisé (profil sans clé) : jamais bloquant pour le passage.
            log.error("[AdminPush] Firebase indisponible : {}", e.getMessage());
            return SendResult.ERROR;
        }
    }

    /** Seul UNREGISTERED prouve que le jeton est mort ; INVALID_ARGUMENT peut venir du contenu. */
    static SendResult mapError(MessagingErrorCode code) {
        return code == MessagingErrorCode.UNREGISTERED ? SendResult.INVALID_TOKEN : SendResult.ERROR;
    }

    /** Sans lien webpush : le service worker ouvre {@code data.route} au clic. */
    static Message buildMessage(String fcmToken, String title, String body, Map<String, String> data) {
        Message.Builder builder = Message.builder()
                .setToken(fcmToken)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build());
        if (data != null) {
            builder.putAllData(data);
        }
        return builder.build();
    }
}
