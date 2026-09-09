package com.yadony.api.auth;

import com.yadony.api.auth.dto.UserDeviceDto;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConnectedDevicesService {

    private static final Logger log = LoggerFactory.getLogger(ConnectedDevicesService.class);

    private final UserDeviceJpaRepository deviceRepo;
    private final UserRepository userRepository;
    private final Optional<FirebaseAuth> firebaseAuth;

    public ConnectedDevicesService(
            UserDeviceJpaRepository deviceRepo,
            UserRepository userRepository,
            Optional<FirebaseAuth> firebaseAuth) {
        this.deviceRepo = deviceRepo;
        this.userRepository = userRepository;
        this.firebaseAuth = firebaseAuth;
    }

    @Transactional(readOnly = true)
    public List<UserDeviceDto> listDevices(UUID userId, String currentDeviceId) {
        return deviceRepo.findByUserIdOrderByLastSeenAtDesc(userId).stream()
                .map(d -> new UserDeviceDto(
                        d.getDeviceId(),
                        d.getDeviceName(),
                        d.getPlatform(),
                        d.getLastSeenAt(),
                        d.getDeviceId().equals(currentDeviceId)
                ))
                .toList();
    }

    @Transactional
    public void revokeDevice(UUID userId, String deviceId, String currentDeviceId) {
        if (deviceId.equals(currentDeviceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Impossible de révoquer l'appareil courant");
        }
        int deleted = deviceRepo.deleteByUserIdAndDeviceId(userId, deviceId);
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Appareil introuvable");
        }
    }

    @Transactional
    public void revokeOthers(UUID userId, String currentDeviceId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));
        if (firebaseAuth.isEmpty()) {
            log.warn("FirebaseAuth non disponible — révocation des tokens Firebase ignorée pour userId={}", userId);
        } else {
            try {
                firebaseAuth.get().revokeRefreshTokens(user.getFirebaseUid());
            } catch (FirebaseAuthException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Erreur lors de la révocation Firebase");
            }
        }
        deviceRepo.deleteByUserIdAndDeviceIdNot(userId, currentDeviceId);
    }

    /**
     * Un jeton FCM désigne un appareil physique, pas un compte : quand un compte l'enregistre,
     * l'appareil ne doit plus recevoir les pushs d'un compte précédent connecté sur le même
     * téléphone. Recette du 2026-09-09 : les deux comptes du test portaient les deux mêmes
     * appareils, chaque push arrivait sur les deux téléphones et ouvrait l'écran d'un autre
     * compte (403). Retire le jeton des lignes {@code user_devices} des autres comptes et de
     * leur colonne héritée {@code users.fcm_token}.
     */
    @Transactional
    public void releaseTokenFromOtherUsers(UUID userId, String fcmToken) {
        if (fcmToken == null || fcmToken.isBlank()) {
            return;
        }
        int released = deviceRepo.deleteByFcmTokenAndUserIdNot(fcmToken, userId);
        int legacyCleared = 0;
        for (UserEntity other : userRepository.findAllByFcmToken(fcmToken)) {
            if (!userId.equals(other.getId())) {
                other.setFcmToken(null);
                userRepository.save(other);
                legacyCleared++;
            }
        }
        if (released > 0 || legacyCleared > 0) {
            log.info("[devices] jeton FCM repris par userId={} : {} appareil(s) et {} colonne(s) héritée(s) "
                    + "d'autres comptes libérés", userId, released, legacyCleared);
        }
    }

    /**
     * Déconnexion de l'appareil courant : il ne doit plus recevoir les pushs de ce compte.
     * Idempotent, jamais d'erreur : l'app l'appelle en meilleur effort juste avant
     * {@code signOut}. Sans identifiant d'appareil, ou sans ligne pour cet identifiant, la
     * colonne héritée est vidée quand même : mieux vaut une push manquée qu'une push sur un
     * téléphone déconnecté.
     */
    @Transactional
    public void forgetDevice(UserEntity user, String deviceId) {
        boolean clearLegacy = true;
        if (deviceId != null && !deviceId.isBlank()) {
            Optional<UserDeviceEntity> device = deviceRepo.findByUserIdAndDeviceId(user.getId(), deviceId);
            if (device.isPresent()) {
                clearLegacy = Objects.equals(device.get().getFcmToken(), user.getFcmToken());
                deviceRepo.delete(device.get());
            }
        }
        if (clearLegacy && user.getFcmToken() != null) {
            user.setFcmToken(null);
            userRepository.save(user);
        }
    }

    @Transactional
    public void upsertDevice(UUID userId, String deviceId, String deviceName,
                              String platform, String fcmToken) {
        deviceRepo.findByUserIdAndDeviceId(userId, deviceId).ifPresentOrElse(
                existing -> {
                    existing.setDeviceName(deviceName);
                    existing.setFcmToken(fcmToken);
                    existing.setPlatform(platform);
                    deviceRepo.save(existing);
                },
                () -> {
                    UserDeviceEntity entity = new UserDeviceEntity();
                    entity.setUserId(userId);
                    entity.setDeviceId(deviceId);
                    entity.setDeviceName(deviceName);
                    entity.setPlatform(platform);
                    entity.setFcmToken(fcmToken);
                    deviceRepo.save(entity);
                }
        );
    }
}
