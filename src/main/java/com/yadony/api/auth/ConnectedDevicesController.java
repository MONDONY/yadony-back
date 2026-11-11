package com.yadony.api.auth;

import com.yadony.api.auth.dto.RegisterDeviceRequest;
import com.yadony.api.auth.dto.UserDeviceDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.yadony.api.common.LocalizedResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/users/me/devices")
@Validated
public class ConnectedDevicesController {

    private final ConnectedDevicesService devicesService;
    private final AuthService authService;

    public ConnectedDevicesController(ConnectedDevicesService devicesService,
                                       AuthService authService) {
        this.devicesService = devicesService;
        this.authService = authService;
    }

    @PostMapping
    public ResponseEntity<Void> registerDevice(@Valid @RequestBody RegisterDeviceRequest request,
                                               HttpServletRequest httpRequest) {
        UUID userId = authService.requireUserId();
        String deviceId = requireDeviceId(httpRequest);
        devicesService.upsertDevice(userId, deviceId, request.deviceName(), request.platform(), null);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<List<UserDeviceDto>> listDevices(HttpServletRequest request) {
        UUID userId = authService.requireUserId();
        String currentDeviceId = requireDeviceId(request);
        return ResponseEntity.ok(devicesService.listDevices(userId, currentDeviceId));
    }

    @DeleteMapping("/others")
    public ResponseEntity<Void> revokeOthers(HttpServletRequest request) {
        UUID userId = authService.requireUserId();
        String currentDeviceId = requireDeviceId(request);
        devicesService.revokeOthers(userId, currentDeviceId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{deviceId}")
    public ResponseEntity<Void> revokeDevice(
            @PathVariable @Size(max = 128) String deviceId,
            HttpServletRequest request) {
        UUID userId = authService.requireUserId();
        String currentDeviceId = requireDeviceId(request);
        devicesService.revokeDevice(userId, deviceId, currentDeviceId);
        return ResponseEntity.noContent().build();
    }

    private String requireDeviceId(HttpServletRequest request) {
        String deviceId = request.getHeader("X-Device-Id");
        if (deviceId == null || deviceId.isBlank()) {
            throw new LocalizedResponseStatusException(HttpStatus.BAD_REQUEST, "problem.devices.header-required");
        }
        return deviceId;
    }
}
