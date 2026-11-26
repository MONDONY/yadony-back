package com.yadony.api.admin.mobile;

import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
