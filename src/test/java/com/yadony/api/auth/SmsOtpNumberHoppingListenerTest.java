package com.yadony.api.auth;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.smsotp.SmsOtpNumberHoppingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Compte qui demande des codes SMS pour plusieurs numéros (08/11/2026) : suspension et alerte
 * CRITICAL du back-office, sans jamais lever (le demandeur doit recevoir son 403).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SmsOtpNumberHoppingListener")
class SmsOtpNumberHoppingListenerTest {

    @Mock UserRepository userRepository;
    @Mock UserService userService;
    @Mock AdminAlertEscalator alerts;
    @Mock PlatformTransactionManager transactionManager;

    SmsOtpNumberHoppingListener listener;

    private static final String UID = "uid-hopper";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final SmsOtpNumberHoppingEvent EVENT = new SmsOtpNumberHoppingEvent(UID, 3, 3);

    @BeforeEach
    void setUp() {
        listener = new SmsOtpNumberHoppingListener(userRepository, userService, alerts, transactionManager);
    }

    private void accountExists() {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("compte avec fiche → suspendu, puis alerte CRITICAL qui le dit")
    void knownAccount_suspendedAndAlerted() {
        accountExists();
        when(userService.suspendAutomatically(eq(USER_ID), eq("sms_otp_multiple_numbers"), anyString(), anyMap()))
                .thenReturn(true);

        listener.onNumberHopping(EVENT);

        verify(userService).suspendAutomatically(eq(USER_ID), eq("sms_otp_multiple_numbers"),
                contains("3 numéros"), argThat(m -> Integer.valueOf(3).equals(m.get("distinctNumbers"))));
        verify(alerts).raiseOnce(eq("SMS_OTP_NUMBERS_" + USER_ID), eq("CRITICAL"),
                contains("suspendu automatiquement"),
                argThat(ctx -> Boolean.TRUE.equals(ctx.get("suspended"))
                        && USER_ID.toString().equals(ctx.get("userId"))));
    }

    @Test
    @DisplayName("compte déjà suspendu → alerte quand même, sans prétendre l'avoir suspendu")
    void alreadySuspended_alertSaysSo() {
        accountExists();
        when(userService.suspendAutomatically(any(), anyString(), anyString(), anyMap())).thenReturn(false);

        listener.onNumberHopping(EVENT);

        verify(alerts).raiseOnce(eq("SMS_OTP_NUMBERS_" + USER_ID), eq("CRITICAL"),
                contains("déjà suspendu"), argThat(ctx -> Boolean.FALSE.equals(ctx.get("suspended"))));
    }

    @Test
    @DisplayName("compte Firebase sans fiche Yadony → pas de suspension possible, alerte qui le dit")
    void noYadonyAccount_alertOnly() {
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.empty());

        listener.onNumberHopping(EVENT);

        verify(userService, never()).suspendAutomatically(any(), anyString(), anyString(), anyMap());
        verify(alerts).raiseOnce(eq("SMS_OTP_NUMBERS_" + UID), eq("CRITICAL"),
                contains("sans fiche Yadony"), anyMap());
    }

    @Test
    @DisplayName("UID Firebase très long → type d'alerte tronqué à 60 caractères")
    void longUid_truncatedAlertType() {
        String longUid = "u".repeat(128);
        when(userRepository.findByFirebaseUid(longUid)).thenReturn(Optional.empty());

        listener.onNumberHopping(new SmsOtpNumberHoppingEvent(longUid, 3, 3));

        verify(alerts).raiseOnce(argThat(type -> type.length() == AdminAlertEscalator.TYPE_MAX_LENGTH),
                eq("CRITICAL"), anyString(), anyMap());
    }

    @Test
    @DisplayName("suspension en échec → alerte quand même, et rien ne remonte au demandeur")
    void suspensionFailure_neverThrows() {
        accountExists();
        when(userService.suspendAutomatically(any(), anyString(), anyString(), anyMap()))
                .thenThrow(new IllegalStateException("base indisponible"));

        assertThatCode(() -> listener.onNumberHopping(EVENT)).doesNotThrowAnyException();

        verify(alerts).raiseOnce(anyString(), eq("CRITICAL"), anyString(), anyMap());
    }

    @Test
    @DisplayName("alerte en échec → rien ne remonte au demandeur")
    void alertFailure_neverThrows() {
        accountExists();
        when(userService.suspendAutomatically(any(), anyString(), anyString(), anyMap())).thenReturn(true);
        when(alerts.raiseOnce(anyString(), anyString(), anyString(), anyMap()))
                .thenThrow(new IllegalArgumentException("type trop long"));

        assertThatCode(() -> listener.onNumberHopping(EVENT)).doesNotThrowAnyException();
    }
}
