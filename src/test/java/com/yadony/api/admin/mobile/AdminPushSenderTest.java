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
    void mapError_onlyUnregisteredRemovesDevice() {
        assertThat(AdminPushSender.mapError(MessagingErrorCode.UNREGISTERED)).isEqualTo(AdminPushSender.SendResult.INVALID_TOKEN);
        assertThat(AdminPushSender.mapError(MessagingErrorCode.INVALID_ARGUMENT)).isEqualTo(AdminPushSender.SendResult.ERROR);
        assertThat(AdminPushSender.mapError(MessagingErrorCode.UNAVAILABLE)).isEqualTo(AdminPushSender.SendResult.ERROR);
        assertThat(AdminPushSender.mapError(MessagingErrorCode.INTERNAL)).isEqualTo(AdminPushSender.SendResult.ERROR);
        assertThat(AdminPushSender.mapError(null)).isEqualTo(AdminPushSender.SendResult.ERROR);
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
        assertThat(ReflectionTestUtils.getField(m, "webpushConfig")).isNull();
    }

    private static AdminPushSender.SendResult sendWith(Throwable failure) throws Exception {
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

    @Test
    void send_ok() throws Exception {
        assertThat(sendWith(null)).isEqualTo(AdminPushSender.SendResult.OK);
    }

    @Test
    void send_unregistered_isInvalidToken() throws Exception {
        FirebaseMessagingException e = mock(FirebaseMessagingException.class);
        when(e.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        assertThat(sendWith(e)).isEqualTo(AdminPushSender.SendResult.INVALID_TOKEN);
    }

    @Test
    void send_otherFirebaseError_isError() throws Exception {
        FirebaseMessagingException e = mock(FirebaseMessagingException.class);
        when(e.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNAVAILABLE);
        assertThat(sendWith(e)).isEqualTo(AdminPushSender.SendResult.ERROR);
    }

    @Test
    void send_firebaseNotInitialised_isError() throws Exception {
        assertThat(sendWith(new IllegalStateException("no app"))).isEqualTo(AdminPushSender.SendResult.ERROR);
    }
}
