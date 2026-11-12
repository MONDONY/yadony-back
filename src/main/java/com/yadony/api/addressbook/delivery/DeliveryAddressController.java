package com.yadony.api.addressbook.delivery;

import com.yadony.api.addressbook.delivery.dto.*;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/addressbook/delivery-addresses")
@PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
public class DeliveryAddressController {

    private final DeliveryAddressService service;
    private final UserRepository userRepository;

    public DeliveryAddressController(DeliveryAddressService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<List<DeliveryAddressDto>> list(@AuthenticationPrincipal String firebaseUid) {
        return ResponseEntity.ok(service.findAll(userId(firebaseUid)));
    }

    @PostMapping
    public ResponseEntity<DeliveryAddressDto> create(
            @AuthenticationPrincipal String firebaseUid,
            @Valid @RequestBody CreateDeliveryAddressRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(userId(firebaseUid), request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DeliveryAddressDto> update(
            @AuthenticationPrincipal String firebaseUid,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDeliveryAddressRequest request) {
        return ResponseEntity.ok(service.update(userId(firebaseUid), id, request));
    }

    @PatchMapping("/{id}/set-default")
    public ResponseEntity<DeliveryAddressDto> setDefault(
            @AuthenticationPrincipal String firebaseUid,
            @PathVariable UUID id) {
        return ResponseEntity.ok(service.setDefault(userId(firebaseUid), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal String firebaseUid,
            @PathVariable UUID id) {
        service.delete(userId(firebaseUid), id);
        return ResponseEntity.noContent().build();
    }

    private UUID userId(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> YadonyNotFoundException.localized("problem.kyc.user-not-found"))
                .getId();
    }
}
