package com.yadony.api.auth;

import com.yadony.api.auth.dto.UserDeviceDto;
import com.google.firebase.auth.FirebaseAuth;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConnectedDevicesServiceTest {

    @Mock UserDeviceJpaRepository deviceRepo;
    @Mock UserRepository userRepository;
    @Mock FirebaseAuth firebaseAuth;

    ConnectedDevicesService service;
    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ConnectedDevicesService(deviceRepo, userRepository, Optional.of(firebaseAuth));
    }

    @Test
    void listDevices_retourneListeAvecFlagIsCurrent() {
        String currentDeviceId = "device-abc";
        UserDeviceEntity dev1 = deviceEntity(userId, "device-abc", "iPhone 14", "ios");
        UserDeviceEntity dev2 = deviceEntity(userId, "device-xyz", "Galaxy S22", "android");
        when(deviceRepo.findByUserIdOrderByLastSeenAtDesc(userId)).thenReturn(List.of(dev1, dev2));

        List<UserDeviceDto> result = service.listDevices(userId, currentDeviceId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).isCurrent()).isTrue();
        assertThat(result.get(1).isCurrent()).isFalse();
    }

    @Test
    void revokeDevice_supprimeLaLigne() {
        String deviceId = "device-xyz";
        when(deviceRepo.deleteByUserIdAndDeviceId(userId, deviceId)).thenReturn(1);

        service.revokeDevice(userId, deviceId, "device-abc");

        verify(deviceRepo).deleteByUserIdAndDeviceId(userId, deviceId);
    }

    @Test
    void revokeDevice_lanceExceptionSiAppareilCourant() {
        assertThatThrownBy(() -> service.revokeDevice(userId, "device-abc", "device-abc"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void revokeDevice_lanceExceptionSiNonTrouve() {
        when(deviceRepo.deleteByUserIdAndDeviceId(userId, "device-xyz")).thenReturn(0);
        assertThatThrownBy(() -> service.revokeDevice(userId, "device-xyz", "device-abc"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void revokeOthers_appelleFirebaseEtSupprimeLesAutres() throws Exception {
        String currentDeviceId = "device-abc";
        UserEntity user = new UserEntity();
        user.setFirebaseUid("uid-123");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.revokeOthers(userId, currentDeviceId);

        verify(firebaseAuth).revokeRefreshTokens("uid-123");
        verify(deviceRepo).deleteByUserIdAndDeviceIdNot(userId, currentDeviceId);
    }

    @Test
    void upsertDevice_creeLEntitesSiAbsente() {
        when(deviceRepo.findByUserIdAndDeviceId(userId, "new-dev")).thenReturn(Optional.empty());
        when(deviceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertDevice(userId, "new-dev", "iPhone 15", "ios", "token123");

        verify(deviceRepo).save(argThat(e ->
            e.getDeviceId().equals("new-dev") && e.getDeviceName().equals("iPhone 15")
        ));
    }

    @Test
    void upsertDevice_acceptePlatformWebSansFcmToken() {
        // La plateforme web n'a pas de token FCM — fcmToken doit être null accepté
        when(deviceRepo.findByUserIdAndDeviceId(userId, "web-session-abc")).thenReturn(Optional.empty());
        when(deviceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertDevice(userId, "web-session-abc", "Chrome / Windows", "web", null);

        verify(deviceRepo).save(argThat(e ->
            e.getDeviceId().equals("web-session-abc")
                && "web".equals(e.getPlatform())
                && e.getFcmToken() == null
        ));
    }

    @Test
    void releaseTokenFromOtherUsers_libereLesAppareilsEtLaColonneHeriteeDesAutresComptes() {
        UserEntity other = userWithToken(UUID.randomUUID(), "token123");
        UserEntity self = userWithToken(userId, "token123");
        when(deviceRepo.deleteByFcmTokenAndUserIdNot("token123", userId)).thenReturn(2);
        when(userRepository.findAllByFcmToken("token123")).thenReturn(List.of(other, self));

        service.releaseTokenFromOtherUsers(userId, "token123");

        verify(deviceRepo).deleteByFcmTokenAndUserIdNot("token123", userId);
        assertThat(other.getFcmToken()).isNull();
        assertThat(self.getFcmToken()).isEqualTo("token123");
        verify(userRepository).save(other);
        verify(userRepository, never()).save(self);
    }

    @Test
    void releaseTokenFromOtherUsers_ignoreUnJetonVide() {
        service.releaseTokenFromOtherUsers(userId, " ");
        service.releaseTokenFromOtherUsers(userId, null);
        verifyNoInteractions(deviceRepo, userRepository);
    }

    @Test
    void forgetDevice_supprimeLaLigneEtVideLaColonneHeriteeSiMemeJeton() {
        UserEntity user = userWithToken(userId, "token123");
        UserDeviceEntity device = deviceEntity(userId, "device-abc", "iPhone 14", "ios");
        device.setFcmToken("token123");
        when(deviceRepo.findByUserIdAndDeviceId(userId, "device-abc")).thenReturn(Optional.of(device));

        service.forgetDevice(user, "device-abc");

        verify(deviceRepo).delete(device);
        assertThat(user.getFcmToken()).isNull();
        verify(userRepository).save(user);
    }

    @Test
    void forgetDevice_gardeLaColonneHeriteeSiElleAppartientAUnAutreAppareil() {
        UserEntity user = userWithToken(userId, "token-autre-appareil");
        UserDeviceEntity device = deviceEntity(userId, "device-abc", "iPhone 14", "ios");
        device.setFcmToken("token123");
        when(deviceRepo.findByUserIdAndDeviceId(userId, "device-abc")).thenReturn(Optional.of(device));

        service.forgetDevice(user, "device-abc");

        verify(deviceRepo).delete(device);
        assertThat(user.getFcmToken()).isEqualTo("token-autre-appareil");
        verify(userRepository, never()).save(any());
    }

    @Test
    void forgetDevice_sansLigneNiIdentifiant_videLaColonneHeritee() {
        UserEntity sansLigne = userWithToken(userId, "token123");
        when(deviceRepo.findByUserIdAndDeviceId(userId, "inconnu")).thenReturn(Optional.empty());

        service.forgetDevice(sansLigne, "inconnu");

        assertThat(sansLigne.getFcmToken()).isNull();
        verify(userRepository).save(sansLigne);
        verify(deviceRepo, never()).delete(any(UserDeviceEntity.class));

        UserEntity sansIdentifiant = userWithToken(userId, "token456");

        service.forgetDevice(sansIdentifiant, null);

        assertThat(sansIdentifiant.getFcmToken()).isNull();
        verify(userRepository).save(sansIdentifiant);
    }

    private UserEntity userWithToken(UUID id, String token) {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", id);
        user.setFcmToken(token);
        return user;
    }

    private UserDeviceEntity deviceEntity(UUID userId, String deviceId, String name, String platform) {
        UserDeviceEntity e = new UserDeviceEntity();
        e.setId(UUID.randomUUID());
        e.setUserId(userId);
        e.setDeviceId(deviceId);
        e.setDeviceName(name);
        e.setPlatform(platform);
        e.setLastSeenAt(OffsetDateTime.now());
        e.setCreatedAt(OffsetDateTime.now());
        return e;
    }
}
