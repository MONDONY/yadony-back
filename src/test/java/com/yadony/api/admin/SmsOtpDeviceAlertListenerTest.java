package com.yadony.api.admin;

import com.yadony.api.smsotp.SmsOtpDeviceNumberHoppingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Alerte « même téléphone sans compte, plusieurs numéros » (08/11/2026) : alerte seulement. */
@DisplayName("SmsOtpDeviceAlertListener")
class SmsOtpDeviceAlertListenerTest {

    private final AdminAlertEscalator alerts = mock(AdminAlertEscalator.class);
    private final SmsOtpDeviceAlertListener listener = new SmsOtpDeviceAlertListener(alerts);

    private static final String DEVICE_ID = "3f2b8c1e-9a4d-4e7b-8c2f-1a2b3c4d5e6f";

    @Test
    @DisplayName("appareil identifié → alerte WARN dédoublonnée par appareil, détail lisible")
    void device_raisesWarnAlert() {
        listener.onDeviceNumberHopping(new SmsOtpDeviceNumberHoppingEvent("device:" + DEVICE_ID, 3, 3));

        verify(alerts).raiseOnce(eq("SMS_OTP_DEVICE_device:" + DEVICE_ID), eq("WARN"),
                contains("appareil " + DEVICE_ID),
                argThat(ctx -> Integer.valueOf(3).equals(ctx.get("distinctNumbers"))
                        && ("device:" + DEVICE_ID).equals(ctx.get("deviceKey"))));
    }

    @Test
    @DisplayName("session invitée → alerte qui le dit")
    void guest_raisesAlert() {
        listener.onDeviceNumberHopping(new SmsOtpDeviceNumberHoppingEvent("guest:abc123", 4, 3));

        verify(alerts).raiseOnce(eq("SMS_OTP_DEVICE_guest:abc123"), eq("WARN"),
                contains("session invitée abc123"), anyMap());
    }

    @Test
    @DisplayName("identifiant long → type tronqué à 60 caractères")
    void longKey_truncated() {
        listener.onDeviceNumberHopping(new SmsOtpDeviceNumberHoppingEvent("guest:" + "u".repeat(128), 3, 3));

        verify(alerts).raiseOnce(argThat(type -> type.length() == AdminAlertEscalator.TYPE_MAX_LENGTH),
                eq("WARN"), anyString(), anyMap());
    }

    @Test
    @DisplayName("alerte en échec → rien ne remonte au demandeur")
    void alertFailure_neverThrows() {
        when(alerts.raiseOnce(anyString(), anyString(), anyString(), anyMap()))
                .thenThrow(new IllegalStateException("base indisponible"));

        assertThatCode(() -> listener.onDeviceNumberHopping(
                new SmsOtpDeviceNumberHoppingEvent("device:" + DEVICE_ID, 3, 3)))
                .doesNotThrowAnyException();
    }
}
