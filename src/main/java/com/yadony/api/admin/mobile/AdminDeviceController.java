package com.yadony.api.admin.mobile;

import com.yadony.api.admin.account.AdminPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Enregistrement des appareils de la PWA admin mobile pour les notifications push. */
@RestController
@RequestMapping("/admin/mobile/devices")
@PreAuthorize("hasRole('ADMIN')")
public class AdminDeviceController {

    public record RegisterAdminDeviceRequest(
            @NotBlank String fcmToken,
            @NotBlank String deviceName,
            @NotBlank String platform) {
    }

    private final AdminDeviceRepository repository;

    public AdminDeviceController(AdminDeviceRepository repository) {
        this.repository = repository;
    }

    /** Idempotent sur {@code fcmToken} : un jeton déjà connu (même supprimé) est repris et rattaché à l'appelant. */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void register(@Valid @RequestBody RegisterAdminDeviceRequest request, Authentication auth) {
        UUID adminId = AdminPrincipal.requireAdminId(auth);
        AdminDeviceEntity device = repository.findAnyByFcmToken(request.fcmToken())
                .orElseGet(AdminDeviceEntity::new);
        device.setAdminId(adminId);
        device.setFcmToken(request.fcmToken());
        device.setDeviceName(request.deviceName());
        device.setPlatform(request.platform());
        device.setDeletedAt(null);
        if (device.getId() == null) {
            device.setLastPushedAt(Instant.now());
        }
        repository.save(device);
    }

    /** Suppression logique, limitée aux appareils de l'appelant ; sans effet sinon (204 dans tous les cas). */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void unregister(@RequestParam("fcmToken") String fcmToken, Authentication auth) {
        UUID adminId = AdminPrincipal.requireAdminId(auth);
        repository.findActiveByAdminId(adminId).stream()
                .filter(d -> d.getFcmToken().equals(fcmToken))
                .forEach(d -> {
                    d.softDelete();
                    repository.save(d);
                });
    }
}
