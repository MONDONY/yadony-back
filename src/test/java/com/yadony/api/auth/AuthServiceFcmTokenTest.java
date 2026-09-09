package com.yadony.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Recette du 2026-09-09 : deux comptes connectés tour à tour sur les mêmes téléphones se
 * partageaient les deux jetons FCM, chaque push arrivait sur les deux appareils. Un jeton
 * n'appartient plus qu'à un compte à la fois, et la déconnexion l'oublie.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService : jeton FCM, un appareil = un compte")
class AuthServiceFcmTokenTest {

    @Mock private UserRepository userRepository;
    @Mock private ConnectedDevicesService connectedDevicesService;
    @InjectMocks private AuthService authService;

    private static final String FIREBASE_UID = "uid-fcm-001";
    private static final UUID USER_ID = UUID.randomUUID();

    private UserEntity user() {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", USER_ID);
        u.setFirebaseUid(FIREBASE_UID);
        return u;
    }

    @Test
    void updateFcmToken_libereLeJetonChezLesAutresComptesAvantDeLEnregistrer() {
        UserEntity u = user();
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(u));

        authService.updateFcmToken(FIREBASE_UID, "token123", "device-abc", "iPhone 14", "ios");

        InOrder inOrder = inOrder(connectedDevicesService, userRepository);
        inOrder.verify(connectedDevicesService).releaseTokenFromOtherUsers(USER_ID, "token123");
        inOrder.verify(userRepository).save(u);
        inOrder.verify(connectedDevicesService).upsertDevice(USER_ID, "device-abc", "iPhone 14", "ios", "token123");
        assertThat(u.getFcmToken()).isEqualTo("token123");
    }

    @Test
    void forgetFcmToken_delegueLOubliDeLAppareilCourant() {
        UserEntity u = user();
        when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(u));

        authService.forgetFcmToken(FIREBASE_UID, "device-abc");

        verify(connectedDevicesService).forgetDevice(u, "device-abc");
    }
}
