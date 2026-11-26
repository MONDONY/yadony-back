package com.yadony.api.admin.mobile;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.WebpushConfig;
import com.google.firebase.messaging.WebpushFcmOptions;
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
        Message.Builder builder = Message.builder()
                .setToken(fcmToken)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build());
        if (data != null) {
            builder.putAllData(data);
            String route = data.get("route");
            if (route != null) {
                builder.setWebpushConfig(WebpushConfig.builder()
                        .setFcmOptions(WebpushFcmOptions.withLink(route))
                        .build());
            }
        }
        try {
            FirebaseMessaging.getInstance().send(builder.build());
            return SendResult.OK;
        } catch (FirebaseMessagingException e) {
            MessagingErrorCode code = e.getMessagingErrorCode();
            if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                log.warn("[AdminPush] Jeton invalide ({}), appareil retiré", code);
                return SendResult.INVALID_TOKEN;
            }
            log.error("[AdminPush] Envoi échoué : {}", e.getMessage());
            return SendResult.ERROR;
        } catch (RuntimeException e) {
            // Firebase non initialisé (profil sans clé) : jamais bloquant pour le passage.
            log.error("[AdminPush] Firebase indisponible : {}", e.getMessage());
            return SendResult.ERROR;
        }
    }
}
