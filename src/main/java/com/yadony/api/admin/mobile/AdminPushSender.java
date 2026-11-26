package com.yadony.api.admin.mobile;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.WebpushConfig;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Envoi FCM vers un appareil admin (PWA, Web Push). Distinct de {@code FcmService}, qui vise les
 * utilisateurs de l'app. Non final : les tests le remplacent par un faux, sans Firebase.
 *
 * <p>Ne journalise rien : le niveau d'un échec dépend de la série en cours sur l'appareil, que seul
 * {@link AdminPushDispatcher} connaît (la première erreur d'une série part en ERROR vers Sentry,
 * les suivantes en WARN).
 */
@Component
public class AdminPushSender {

    /**
     * {@code INVALID_TOKEN} : le jeton ne marchera plus jamais, l'appareil est retiré.
     * {@code ERROR} : refus de cet envoi (jeton malformé, quota de l'appareil, contenu), retenté,
     * l'appareil est retiré après une série. {@code SERVICE_ERROR} : FCM indisponible ou mal
     * configuré côté serveur, jamais imputable à l'appareil, qui reste abonné.
     */
    public enum SendResult { OK, INVALID_TOKEN, ERROR, SERVICE_ERROR }

    /** Résultat d'un envoi ; {@code detail} porte le message de l'échec, pour le journal. */
    public record SendOutcome(SendResult result, String detail) {}

    public SendOutcome send(String fcmToken, String title, String body, Map<String, String> data) {
        try {
            FirebaseMessaging.getInstance().send(buildMessage(fcmToken, title, body, data));
            return new SendOutcome(SendResult.OK, null);
        } catch (FirebaseMessagingException e) {
            return new SendOutcome(mapError(e.getMessagingErrorCode()), e.getMessage());
        } catch (RuntimeException e) {
            // Firebase non initialisé (profil sans clé) : jamais bloquant pour le passage.
            return new SendOutcome(SendResult.SERVICE_ERROR, "Firebase indisponible : " + e.getMessage());
        }
    }

    /**
     * UNREGISTERED et SENDER_ID_MISMATCH (jeton d'un autre projet Firebase) prouvent que le jeton
     * est mort. INVALID_ARGUMENT peut venir du contenu : simple erreur, retentée.
     */
    static SendResult mapError(MessagingErrorCode code) {
        if (code == null) {
            return SendResult.ERROR;
        }
        return switch (code) {
            case UNREGISTERED, SENDER_ID_MISMATCH -> SendResult.INVALID_TOKEN;
            case UNAVAILABLE, INTERNAL, THIRD_PARTY_AUTH_ERROR -> SendResult.SERVICE_ERROR;
            default -> SendResult.ERROR;
        };
    }

    /** Un push vieux de plus d'un jour n'a plus d'intérêt : le service push le jette au-delà. */
    static final String TTL_SECONDS = "86400";

    /**
     * Sans lien webpush ({@code fcm_options.link}) : le service worker ouvre {@code data.route} au
     * clic. Seuls les en-têtes Web Push sont posés : durée de vie (sinon un téléphone éteint
     * recevrait le push des semaines plus tard) et urgence haute (livré même en économie d'énergie).
     */
    static Message buildMessage(String fcmToken, String title, String body, Map<String, String> data) {
        Message.Builder builder = Message.builder()
                .setToken(fcmToken)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .setWebpushConfig(WebpushConfig.builder()
                        .putHeader("TTL", TTL_SECONDS)
                        .putHeader("Urgency", "high")
                        .build());
        if (data != null) {
            builder.putAllData(data);
        }
        return builder.build();
    }
}
