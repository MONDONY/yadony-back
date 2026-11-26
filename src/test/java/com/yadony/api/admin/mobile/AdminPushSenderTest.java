package com.yadony.api.admin.mobile;

import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class AdminPushSenderTest {

    @Test
    void mapError_deadTokensAreInvalid() {
        assertThat(AdminPushSender.mapError(MessagingErrorCode.UNREGISTERED)).isEqualTo(AdminPushSender.SendResult.INVALID_TOKEN);
        // Jeton d'un autre projet Firebase : il ne marchera jamais ici (F4).
        assertThat(AdminPushSender.mapError(MessagingErrorCode.SENDER_ID_MISMATCH)).isEqualTo(AdminPushSender.SendResult.INVALID_TOKEN);
    }

    @Test
    void mapError_sendRefusalsAreErrors() {
        // INVALID_ARGUMENT peut venir du contenu : retenté, retiré seulement après une série.
        assertThat(AdminPushSender.mapError(MessagingErrorCode.INVALID_ARGUMENT)).isEqualTo(AdminPushSender.SendResult.ERROR);
        assertThat(AdminPushSender.mapError(MessagingErrorCode.QUOTA_EXCEEDED)).isEqualTo(AdminPushSender.SendResult.ERROR);
        assertThat(AdminPushSender.mapError(null)).isEqualTo(AdminPushSender.SendResult.ERROR);
    }

    @Test
    void mapError_serviceSideFailuresAreNeverTheDevicesFault() {
        assertThat(AdminPushSender.mapError(MessagingErrorCode.UNAVAILABLE)).isEqualTo(AdminPushSender.SendResult.SERVICE_ERROR);
        assertThat(AdminPushSender.mapError(MessagingErrorCode.INTERNAL)).isEqualTo(AdminPushSender.SendResult.SERVICE_ERROR);
        assertThat(AdminPushSender.mapError(MessagingErrorCode.THIRD_PARTY_AUTH_ERROR)).isEqualTo(AdminPushSender.SendResult.SERVICE_ERROR);
    }

    @Test
    void buildMessage_hasTokenTitleBodyData_andNoWebpushLink() {
        Map<String, String> data = Map.of("type", "ADMIN_ALERT", "route", "/m/alertes/1");
        Message m = AdminPushSender.buildMessage("tok", "Titre", "Corps", data);
        assertThat(ReflectionTestUtils.getField(m, "token")).isEqualTo("tok");
        assertThat(ReflectionTestUtils.getField(m, "data")).isEqualTo(data);
        Object n = ReflectionTestUtils.getField(m, "notification");
        assertThat(ReflectionTestUtils.getField(n, "title")).isEqualTo("Titre");
        assertThat(ReflectionTestUtils.getField(n, "body")).isEqualTo("Corps");
        // Le service worker ouvre data.route : jamais de fcm_options.link, ni de contenu webpush.
        Object webpush = ReflectionTestUtils.getField(m, "webpushConfig");
        assertThat(webpush).isNotNull();
        assertThat(ReflectionTestUtils.getField(webpush, "fcmOptions")).isNull();
        assertThat(ReflectionTestUtils.getField(webpush, "notification")).isNull();
        assertThat(ReflectionTestUtils.getField(webpush, "data")).isNull();
    }

    /** Sans TTL, un push resté en attente (téléphone éteint) arriverait des semaines après (F10). */
    @Test
    void buildMessage_webpushHeaders_ttlOneDay_urgencyHigh() {
        Message m = AdminPushSender.buildMessage("tok", "Titre", "Corps", Map.of());
        Object webpush = ReflectionTestUtils.getField(m, "webpushConfig");
        assertThat(webpush).isNotNull();
        assertThat(ReflectionTestUtils.getField(webpush, "headers"))
                .isEqualTo(Map.of("TTL", "86400", "Urgency", "high"));
    }

    private static AdminPushSender.SendOutcome sendWith(Throwable failure) throws Exception {
        FirebaseMessaging fcm = mock(FirebaseMessaging.class);
        if (failure != null) {
            when(fcm.send(any(Message.class))).thenThrow(failure);
        } else {
            when(fcm.send(any(Message.class))).thenReturn("id");
        }
        try (MockedStatic<FirebaseMessaging> st = mockStatic(FirebaseMessaging.class)) {
            st.when(FirebaseMessaging::getInstance).thenReturn(fcm);
            return new AdminPushSender().send("tok", "T", "B", Map.of());
        }
    }

    private static FirebaseMessagingException fcmError(MessagingErrorCode code, String message) {
        FirebaseMessagingException e = mock(FirebaseMessagingException.class);
        when(e.getMessagingErrorCode()).thenReturn(code);
        when(e.getMessage()).thenReturn(message);
        return e;
    }

    @Test
    void send_ok() throws Exception {
        assertThat(sendWith(null).result()).isEqualTo(AdminPushSender.SendResult.OK);
    }

    @Test
    void send_unregistered_isInvalidToken() throws Exception {
        assertThat(sendWith(fcmError(MessagingErrorCode.UNREGISTERED, "gone")).result())
                .isEqualTo(AdminPushSender.SendResult.INVALID_TOKEN);
    }

    @Test
    void send_senderIdMismatch_isInvalidToken() throws Exception {
        assertThat(sendWith(fcmError(MessagingErrorCode.SENDER_ID_MISMATCH, "mismatch")).result())
                .isEqualTo(AdminPushSender.SendResult.INVALID_TOKEN);
    }

    @Test
    void send_invalidArgument_isError_withFcmMessageForTheDispatcherLog() throws Exception {
        AdminPushSender.SendOutcome outcome = sendWith(fcmError(MessagingErrorCode.INVALID_ARGUMENT, "bad token"));
        assertThat(outcome.result()).isEqualTo(AdminPushSender.SendResult.ERROR);
        assertThat(outcome.detail()).isEqualTo("bad token");
    }

    @Test
    void send_unavailable_isServiceError() throws Exception {
        assertThat(sendWith(fcmError(MessagingErrorCode.UNAVAILABLE, "down")).result())
                .isEqualTo(AdminPushSender.SendResult.SERVICE_ERROR);
    }

    @Test
    void send_firebaseNotInitialised_isServiceError() throws Exception {
        AdminPushSender.SendOutcome outcome = sendWith(new IllegalStateException("no app"));
        assertThat(outcome.result()).isEqualTo(AdminPushSender.SendResult.SERVICE_ERROR);
        assertThat(outcome.detail()).contains("no app");
    }

    /** Le niveau dépend de la série d'échecs de l'appareil : seul le répartiteur la connaît (F4). */
    @Test
    void send_neverLogsAtErrorLevel() throws Exception {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AdminPushSender.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            sendWith(fcmError(MessagingErrorCode.INVALID_ARGUMENT, "bad token"));
            sendWith(fcmError(MessagingErrorCode.UNAVAILABLE, "down"));
            sendWith(new IllegalStateException("no app"));
            assertThat(logs.list).noneMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR);
        } finally {
            logger.detachAppender(logs);
        }
    }
}
